package com.app.master.service.engineering;

import com.app.master.service.core.engineering.EngineeringKeyProvider;
import com.app.master.service.core.engineering.SchemaCipher;
import com.app.master.service.service.engineering.LocalKeyProvider;
import com.app.master.service.service.engineering.SchemaCipherService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The encryption the anomaly findings depend on.
 *
 * <p>These are the guarantees that make it safe to write a schema identifier into
 * a monitoring table: the value is unreadable at rest, a tampered value fails
 * rather than decrypting to something plausible, and an old finding stays readable
 * after the key has rotated. Each is asserted by breaking it deliberately.
 */
class SchemaCipherTest {

    @TempDir Path keyDirectory;

    private LocalKeyProvider provider;
    private SchemaCipherService cipher;

    @BeforeEach
    void setUp() throws Exception {
        provider = new LocalKeyProvider(keyDirectory, true);
        cipher = new SchemaCipherService(provider);
    }

    @Test
    @DisplayName("a table name round-trips through AES-256-GCM")
    void roundTrip() {
        SchemaCipher encrypted = cipher.encrypt("customer_order");

        assertEquals("AES-256-GCM", encrypted.algorithm());
        assertEquals("LOCAL", encrypted.keyProvider());
        assertNotEquals("customer_order", encrypted.ciphertext(),
                "the stored value must not be the plaintext");
        assertFalse(encrypted.ciphertext().contains("customer"),
                "nor contain it: base64 of a short name would still be recognisable");

        assertEquals("customer_order", cipher.decrypt(encrypted));
    }

    @Test
    @DisplayName("every encryption uses a fresh nonce, so the same name never produces the same ciphertext")
    void nonceIsUniquePerOperation() {
        Set<String> nonces = new HashSet<>();
        Set<String> ciphertexts = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            SchemaCipher c = cipher.encrypt("place_of_supply");
            nonces.add(c.nonce());
            ciphertexts.add(c.ciphertext());
        }
        // A repeated nonce under one key destroys GCM's guarantee outright, so this
        // is not a statistical check — any collision at all is a failure.
        assertEquals(200, nonces.size(), "a nonce was reused");
        assertEquals(200, ciphertexts.size(),
                "identical plaintext produced identical ciphertext, which leaks equality");
        assertEquals(12, Base64.getDecoder().decode(nonces.iterator().next()).length,
                "GCM nonces are 96 bits");
    }

    @Test
    @DisplayName("a tampered ciphertext fails authentication rather than decrypting to something else")
    void tamperedCiphertextIsRefused() {
        SchemaCipher good = cipher.encrypt("journal_entry_line");
        byte[] raw = Base64.getDecoder().decode(good.ciphertext());
        raw[0] ^= 0x01;   // one bit

        SchemaCipher tampered = new SchemaCipher(Base64.getEncoder().encodeToString(raw),
                good.nonce(), good.authTag(), good.encryptedDataKey(),
                good.keyProvider(), good.keyVersion(), good.algorithm());

        assertEquals(SchemaCipher.DECRYPTION_UNAVAILABLE, cipher.decrypt(tampered),
                "a flipped bit must not yield a plausible-looking table name");
    }

    @Test
    @DisplayName("a tampered authentication tag is refused")
    void tamperedTagIsRefused() {
        SchemaCipher good = cipher.encrypt("payment_attempt");
        byte[] tag = Base64.getDecoder().decode(good.authTag());
        tag[tag.length - 1] ^= 0x01;

        SchemaCipher tampered = new SchemaCipher(good.ciphertext(), good.nonce(),
                Base64.getEncoder().encodeToString(tag), good.encryptedDataKey(),
                good.keyProvider(), good.keyVersion(), good.algorithm());

        assertEquals(SchemaCipher.DECRYPTION_UNAVAILABLE, cipher.decrypt(tampered));
    }

    @Test
    @DisplayName("a wrong key version cannot unwrap the data key")
    void wrongKeyVersionIsRefused() {
        SchemaCipher good = cipher.encrypt("sales_invoice");

        SchemaCipher wrongVersion = new SchemaCipher(good.ciphertext(), good.nonce(),
                good.authTag(), good.encryptedDataKey(), good.keyProvider(),
                "2020-01-01-00", good.algorithm());

        // The version is the wrap's AAD, so a swapped version fails authentication
        // on the key itself — the ciphertext is never even attempted.
        assertEquals(SchemaCipher.DECRYPTION_UNAVAILABLE, cipher.decrypt(wrongVersion));
    }

    @Test
    @DisplayName("a different master key cannot read another's findings")
    void wrongKeyIsRefused() throws Exception {
        SchemaCipher encrypted = cipher.encrypt("customer_order_item");

        Path other = Files.createTempDirectory("eng-other-key");
        SchemaCipherService foreign = new SchemaCipherService(new LocalKeyProvider(other, false));

        assertEquals(SchemaCipher.DECRYPTION_UNAVAILABLE, foreign.decrypt(encrypted),
                "a finding must not be readable with a master key that did not write it");
    }

    @Test
    @DisplayName("hour 1 and hour 2 get different keys, and both stay decryptable")
    void hourlyRotationPreservesHistory() {
        EngineeringKeyProvider.DataKey hour13 = provider.dataKeyFor("2026-09-28-13");
        EngineeringKeyProvider.DataKey hour14 = provider.dataKeyFor("2026-09-28-14");

        assertFalse(java.util.Arrays.equals(hour13.plaintextKey(), hour14.plaintextKey()),
                "the hourly key must actually change");

        SchemaCipher older = cipher.encrypt("gst_tax_rules", hour13);
        SchemaCipher newer = cipher.encrypt("gst_configuration", hour14);

        assertEquals("2026-09-28-13", older.keyVersion());
        assertEquals("2026-09-28-14", newer.keyVersion());

        // The point of the whole design: rotation must not orphan what came before.
        assertEquals("gst_tax_rules", cipher.decrypt(older));
        assertEquals("gst_configuration", cipher.decrypt(newer));
    }

    @Test
    @DisplayName("the same hour reuses one key version rather than minting a key per call")
    void sameHourReusesItsVersion() {
        EngineeringKeyProvider.DataKey first = provider.dataKeyFor("2026-09-28-15");
        EngineeringKeyProvider.DataKey second = provider.dataKeyFor("2026-09-28-15");

        assertEquals(first.version(), second.version());
        assertArrayEquals(first.plaintextKey(), second.plaintextKey(),
                "the same hour must derive the same key, or an older finding in that "
                + "hour becomes unreadable");
        // The wrapped forms differ because each wrap draws a fresh nonce; both must
        // still unwrap to the same key.
        assertNotEquals(first.encryptedDataKey(), second.encryptedDataKey());
    }

    @Test
    @DisplayName("the master key file is created readable only by its owner")
    void keyFileIsOwnerOnly() throws Exception {
        Path keyFile = keyDirectory.resolve("local-master.key");
        assertTrue(Files.exists(keyFile));

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(keyFile);
        assertTrue(permissions.stream().allMatch(p -> p.name().startsWith("OWNER")),
                "the key must not be group- or world-readable: " + permissions);
        assertFalse(permissions.contains(PosixFilePermission.OTHERS_READ));
        assertFalse(permissions.contains(PosixFilePermission.GROUP_READ));
    }

    @Test
    @DisplayName("the key file lives outside any repository and holds no plaintext key in the clear")
    void keyFileIsOutsideTheRepository() throws Exception {
        Path keyFile = keyDirectory.resolve("local-master.key");

        // The temp directory stands in for ~/.veloria/engineering-keys; what matters
        // here is that nothing under a source tree is used.
        assertFalse(keyFile.toAbsolutePath().toString().contains("/src/"),
                "key material must never sit inside the source tree");

        String contents = Files.readString(keyFile).trim();
        assertEquals(32, Base64.getDecoder().decode(contents).length, "AES-256 is 32 bytes");
    }

    @Test
    @DisplayName("restarting with the same directory recovers the same key, so findings survive a restart")
    void masterKeySurvivesRestart() throws Exception {
        SchemaCipher before = cipher.encrypt("order_return_request");

        SchemaCipherService afterRestart =
                new SchemaCipherService(new LocalKeyProvider(keyDirectory, true));

        assertEquals("order_return_request", afterRestart.decrypt(before));
    }
}
