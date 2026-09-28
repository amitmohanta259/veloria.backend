package com.app.master.service.core.engineering;

/**
 * Supplies the hourly data key that encrypts schema identifiers.
 *
 * <p>The one seam between the anomaly system and key management. The scanner, the
 * finding service, the dashboard, Transaction 360 and Compliance Data Gaps all go
 * through {@link SchemaCipherService} and never touch a provider directly, so
 * switching from local development keys to AWS KMS is a configuration change
 * rather than a rewrite.
 *
 * <p><b>Two operations, deliberately.</b> A provider can mint a fresh wrapped key
 * for the current hour, and it can unwrap one it minted earlier. It is never asked
 * to store anything: the wrapped key lives on the finding, which is what makes a
 * finding decryptable years later without a key archive that could lose an hour.
 */
public interface EngineeringKeyProvider {

    /** {@code LOCAL} or {@code AWS_KMS} — recorded on every finding. */
    String providerId();

    /**
     * The data key for the current hour, wrapped.
     *
     * <p>Implementations must return the <em>same</em> key version within one
     * clock hour and a new one after it: rotation is by the hour, not by the call
     * (§15), so two findings written in the same hour share a version and a scan
     * does not mint a key per row.
     */
    DataKey currentDataKey();

    /**
     * Unwraps a key that {@link #currentDataKey()} produced earlier.
     *
     * @throws KeyUnavailableException when the version cannot be recovered — never
     *         a fallback to another key, to plaintext, or to a guess
     */
    byte[] unwrap(String keyVersion, String encryptedDataKey) throws KeyUnavailableException;

    /**
     * A plaintext key for encrypting now, and the wrapped form to persist.
     *
     * <p>The plaintext array is the caller's to use immediately and then clear; it
     * is never logged, never returned from an API, and never written to the
     * database.
     */
    record DataKey(String version, byte[] plaintextKey, String encryptedDataKey) {}

    /** A key version exists on a finding but cannot be recovered. */
    class KeyUnavailableException extends Exception {
        public KeyUnavailableException(String message) { super(message); }
        public KeyUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
