package com.app.master.service.service.engineering;

import com.app.master.service.core.engineering.EngineeringKeyProvider;
import com.app.master.service.core.engineering.SchemaCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts and decrypts schema identifiers with AES-256-GCM.
 *
 * <p>The only class in the anomaly system that performs cryptography, and the only
 * one that holds a plaintext key — for the duration of one call, in a local array
 * it clears afterwards. Everything else deals in {@link SchemaCipher} values.
 *
 * <p><b>Why GCM and not a bare cipher.</b> A table name is short and guessable, so
 * confidentiality alone is not enough: without authentication, a tampered
 * ciphertext would decrypt to some other plausible-looking name and an engineer
 * would act on it. GCM's tag makes tampering a failure rather than a wrong answer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchemaCipherService {

    public static final String ALGORITHM = "AES-256-GCM";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int TAG_BYTES = GCM_TAG_BITS / 8;
    private static final int NONCE_BYTES = 12;

    private final EngineeringKeyProvider keyProvider;
    private final SecureRandom random = new SecureRandom();

    /** The provider recorded on findings written now — {@code LOCAL} or {@code AWS_KMS}. */
    public String providerId() { return keyProvider.providerId(); }

    /**
     * Encrypts one identifier under the current hour's key.
     *
     * <p>Callers encrypting a table and a column for the same finding should pass
     * the same {@link EngineeringKeyProvider.DataKey} to
     * {@link #encrypt(String, EngineeringKeyProvider.DataKey)} so both share a key
     * version — but never a nonce, which is fresh per call either way.
     */
    public SchemaCipher encrypt(String plaintext) {
        return encrypt(plaintext, keyProvider.currentDataKey());
    }

    /** The current hour's key, for encrypting a table and a column under one version. */
    public EngineeringKeyProvider.DataKey currentDataKey() {
        return keyProvider.currentDataKey();
    }

    public SchemaCipher encrypt(String plaintext, EngineeringKeyProvider.DataKey dataKey) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Nothing to encrypt");
        }
        byte[] key = dataKey.plaintextKey().clone();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] combined = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Java appends the tag to the ciphertext. They are split apart here
            // because the finding stores them in separate columns, which makes a
            // truncated or swapped tag a visible integrity failure rather than a
            // silently short ciphertext.
            int ctLength = combined.length - TAG_BYTES;
            byte[] ciphertext = Arrays.copyOfRange(combined, 0, ctLength);
            byte[] tag = Arrays.copyOfRange(combined, ctLength, combined.length);

            return new SchemaCipher(
                    Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(tag),
                    dataKey.encryptedDataKey(),
                    keyProvider.providerId(),
                    dataKey.version(),
                    ALGORITHM);
        } catch (Exception e) {
            // No plaintext, no key, no nonce in the message.
            throw new IllegalStateException("Schema identifier encryption failed", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Decrypts one identifier, server-side.
     *
     * @return the plaintext, or {@link SchemaCipher#DECRYPTION_UNAVAILABLE} when the
     *         key version cannot be recovered or the value fails authentication.
     *         Never a fallback to another key, to Base64, or to a guess.
     */
    public String decrypt(SchemaCipher value) {
        if (value == null) return SchemaCipher.DECRYPTION_UNAVAILABLE;

        // A finding encrypted under a different provider is not decryptable here.
        // Reporting it as unavailable rather than attempting it is the honest
        // answer: a LOCAL finding read after the switch to KMS has no KMS key.
        if (!keyProvider.providerId().equals(value.keyProvider())) {
            log.warn("Anomaly schema value was encrypted by provider {} but the active provider is {}",
                    value.keyProvider(), keyProvider.providerId());
            return SchemaCipher.DECRYPTION_UNAVAILABLE;
        }

        byte[] key = null;
        try {
            key = keyProvider.unwrap(value.keyVersion(), value.encryptedDataKey());

            byte[] ciphertext = Base64.getDecoder().decode(value.ciphertext());
            byte[] tag = Base64.getDecoder().decode(value.authTag());
            byte[] nonce = Base64.getDecoder().decode(value.nonce());

            byte[] combined = new byte[ciphertext.length + tag.length];
            System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
            System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return new String(cipher.doFinal(combined), StandardCharsets.UTF_8);
        } catch (EngineeringKeyProvider.KeyUnavailableException e) {
            log.warn("Anomaly schema value could not be decrypted: key version {} unavailable",
                    value.keyVersion());
            return SchemaCipher.DECRYPTION_UNAVAILABLE;
        } catch (Exception e) {
            // A tampered ciphertext, a tampered tag and a wrong nonce all land
            // here. They are indistinguishable by design — an integrity failure is
            // not a decryption result, and saying which part failed would help an
            // attacker probe.
            log.warn("Anomaly schema value failed authentication under key version {}",
                    value.keyVersion());
            return SchemaCipher.DECRYPTION_UNAVAILABLE;
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }
}
