package com.app.master.service.core.engineering;

/**
 * One encrypted schema identifier — a table name or a column name.
 *
 * <p>Everything needed to decrypt the value later travels with it, because a
 * finding written under one hourly key must still be readable after the key has
 * rotated many times. {@code encryptedDataKey} is the hour's AES key wrapped by
 * the provider's master key, so the plaintext data key is never persisted
 * anywhere; {@code keyProvider} records which provider wrapped it, so a future
 * migration can tell a development finding from a KMS-encrypted one without
 * guessing.
 *
 * <p>The nonce and tag are per <em>value</em>, not per row: a finding carries
 * two encrypted identifiers and therefore two nonces. Reusing one nonce across
 * both would break GCM's security guarantee entirely.
 */
public record SchemaCipher(
        String ciphertext,
        String nonce,
        String authTag,
        String encryptedDataKey,
        String keyProvider,
        String keyVersion,
        String algorithm
) {

    /** What an authorized reader is shown when the key version cannot be loaded. */
    public static final String DECRYPTION_UNAVAILABLE = "DECRYPTION_UNAVAILABLE";

    /** What an unauthorized reader is shown instead of any ciphertext at all. */
    public static final String ENCRYPTED = "ENCRYPTED";
}
