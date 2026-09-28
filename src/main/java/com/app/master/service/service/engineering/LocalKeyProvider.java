package com.app.master.service.service.engineering;

import com.app.master.service.core.engineering.EngineeringKeyProvider;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Set;

/**
 * Development-only key provider. <b>Not production safe, not staging safe.</b>
 *
 * <p>It exists so Engineering work is not blocked while AWS KMS is unprovisioned.
 * It is not a KMS replacement and does not close that gate: the master key sits in
 * a file on one developer's machine, with no audit trail, no access control beyond
 * filesystem permissions, no rotation of the master key itself and no hardware
 * protection. {@link EngineeringCryptoConfig} refuses to create this bean outside
 * a local environment for exactly that reason.
 *
 * <h2>Design</h2>
 * <pre>
 * local master key (file, 0600, outside every repository)
 *        ↓ AES-256-GCM wrap
 * hourly AES-256 data key  →  version 2026-09-28-13
 *        ↓
 * AES-256-GCM encrypt table / column
 * </pre>
 *
 * <p>The hourly key is <em>derived deterministically</em> from the master key and
 * the hour stamp rather than generated randomly and stored. That is what makes an
 * old finding decryptable: recovering hour 13's key needs only the master key and
 * the string {@code 2026-09-28-13}, so there is no per-hour key file to lose, and
 * nothing to delete on rotation. The wrapped key is still persisted on the finding
 * so the same code path works unchanged for KMS, where the key really is random
 * and really must travel with the record.
 */
@Slf4j
public class LocalKeyProvider implements EngineeringKeyProvider {

    public static final String PROVIDER_ID = "LOCAL";
    static final String KEY_FILE_NAME = "local-master.key";

    private static final String WRAP_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private static final int KEY_BYTES = 32;   // AES-256
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");

    private final SecureRandom random = new SecureRandom();
    private final byte[] masterKey;
    private final Path keyFile;

    /**
     * Loads the master key, generating it on first use.
     *
     * @param directory where key material lives — outside any repository
     * @param requireSecurePermissions fail rather than warn when the file mode
     *        cannot be restricted (§14)
     */
    public LocalKeyProvider(Path directory, boolean requireSecurePermissions) throws IOException {
        this.keyFile = directory.resolve(KEY_FILE_NAME);
        this.masterKey = loadOrCreate(directory, requireSecurePermissions);
    }

    @Override
    public String providerId() { return PROVIDER_ID; }

    @Override
    public DataKey currentDataKey() {
        String version = ZonedDateTime.now(ZoneOffset.UTC).format(HOUR);
        return dataKeyFor(version);
    }

    /**
     * The key for a given hour — the same bytes every time, for any given master key.
     *
     * <p>Public so historical recovery can be tested for a past hour without waiting
     * for the clock. It mints nothing persistent: the hourly key is derived, so
     * asking for an old hour recreates exactly the key that hour used.
     */
    public DataKey dataKeyFor(String version) {
        byte[] plaintext = deriveHourKey(version);
        return new DataKey(version, plaintext, wrap(plaintext, version));
    }

    @Override
    public byte[] unwrap(String keyVersion, String encryptedDataKey) throws KeyUnavailableException {
        if (keyVersion == null || keyVersion.isBlank()) {
            throw new KeyUnavailableException("The finding carries no key version");
        }
        try {
            byte[] blob = Base64.getDecoder().decode(encryptedDataKey);
            if (blob.length <= NONCE_BYTES) {
                throw new KeyUnavailableException("The wrapped key is truncated");
            }
            byte[] nonce = new byte[NONCE_BYTES];
            System.arraycopy(blob, 0, nonce, 0, NONCE_BYTES);
            byte[] ct = new byte[blob.length - NONCE_BYTES];
            System.arraycopy(blob, NONCE_BYTES, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance(WRAP_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            // The version is authenticated, not encrypted: a wrapped key cannot be
            // replayed under a different hour stamp.
            cipher.updateAAD(keyVersion.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return cipher.doFinal(ct);
        } catch (KeyUnavailableException e) {
            throw e;
        } catch (Exception e) {
            // Deliberately does not name the cause in the message: an
            // authentication failure and a corrupt blob must look the same to a
            // caller, and neither may leak anything about the key.
            throw new KeyUnavailableException(
                    "The data key for version " + keyVersion + " could not be recovered", e);
        }
    }

    // ── master key file ──────────────────────────────────────────────────────

    private byte[] loadOrCreate(Path directory, boolean requireSecurePermissions) throws IOException {
        Files.createDirectories(directory);
        restrictDirectory(directory, requireSecurePermissions);

        if (Files.exists(keyFile)) {
            byte[] key = Base64.getDecoder().decode(Files.readString(keyFile).trim());
            if (key.length != KEY_BYTES) {
                throw new IOException("The local master key at " + keyFile
                        + " is not " + KEY_BYTES + " bytes; delete it to have a new one generated");
            }
            verifyPermissions(requireSecurePermissions);
            log.info("Engineering local key provider: master key loaded from {} (development only)", keyFile);
            return key;
        }

        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        // Created empty with the right mode BEFORE any key material is written, so
        // the secret is never briefly world-readable.
        createRestricted(keyFile, requireSecurePermissions);
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(key),
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        verifyPermissions(requireSecurePermissions);
        // The path, never the key.
        log.warn("Engineering local key provider: a new development master key was generated at {}. "
                + "This is DEVELOPMENT ONLY and is not a substitute for AWS KMS.", keyFile);
        return key;
    }

    private void createRestricted(Path file, boolean required) throws IOException {
        Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(ownerOnly));
        } catch (UnsupportedOperationException e) {
            if (required) {
                throw new IOException("This filesystem cannot restrict file permissions, and "
                        + "engineering.crypto.local.require-secure-permissions is true", e);
            }
            log.warn("Engineering local key provider: this filesystem cannot set 0600; "
                    + "the master key file is not permission-restricted");
            Files.createFile(file);
        }
    }

    private void restrictDirectory(Path directory, boolean required) throws IOException {
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException e) {
            if (required) {
                throw new IOException("Could not restrict permissions on " + directory, e);
            }
            log.warn("Engineering local key provider: could not restrict permissions on {}", directory);
        }
    }

    private void verifyPermissions(boolean required) throws IOException {
        try {
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(keyFile);
            boolean ownerOnly = actual.stream().allMatch(p -> p.name().startsWith("OWNER"));
            if (!ownerOnly) {
                String message = "The local master key at " + keyFile
                        + " is readable beyond its owner (" + PosixFilePermissions.toString(actual) + ")";
                if (required) throw new IOException(message);
                log.warn("Engineering local key provider: {}", message);
            }
        } catch (UnsupportedOperationException e) {
            if (required) throw new IOException("Cannot read POSIX permissions of " + keyFile, e);
        }
    }

    // ── key derivation and wrapping ──────────────────────────────────────────

    /**
     * The hour's key, from the master key and the hour stamp.
     *
     * <p>HMAC-SHA256 over the version string, keyed by the master key — a standard
     * KDF construction, which is what makes the same hour yield the same key and a
     * different hour a different one. This is <em>not</em> the "environment secret
     * + HKDF" pattern the earlier phases refused: that was rejected as a substitute
     * for KMS in production, and this provider is confined to local development by
     * {@link EngineeringCryptoConfig}.
     */
    private byte[] deriveHourKey(String version) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
            mac.update("engineering-anomaly-schema/".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return mac.doFinal(version.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not derive the hourly data key", e);
        }
    }

    /**
     * nonce ‖ ciphertext(+tag), base64. The version is the AAD.
     *
     * <p>The version is a parameter rather than read from the clock: wrapping a key
     * for a past hour with the current hour as AAD would produce a blob that could
     * never be unwrapped under its own version, which is precisely the historical
     * recovery this design exists to guarantee.
     */
    private String wrap(byte[] plaintextKey, String version) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(WRAP_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(version.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintextKey);
            byte[] blob = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, blob, 0, nonce.length);
            System.arraycopy(ct, 0, blob, nonce.length, ct.length);
            return Base64.getEncoder().encodeToString(blob);
        } catch (Exception e) {
            throw new IllegalStateException("Could not wrap the hourly data key", e);
        }
    }

    /** Only for the wrap/unwrap symmetry test; the production path never needs it. */
    static byte[] freshKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(KEY_BYTES * 8);
            return generator.generateKey().getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
