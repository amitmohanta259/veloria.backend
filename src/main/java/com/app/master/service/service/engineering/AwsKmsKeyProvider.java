package com.app.master.service.service.engineering;

import com.app.master.service.core.engineering.EngineeringKeyProvider;

/**
 * The production provider. <b>Not implemented — Gate 1 (AWS KMS) is ON HOLD.</b>
 *
 * <p>This class exists so the seam is real rather than promised. It is selected by
 * {@code engineering.crypto.provider=aws-kms} and, until a CMK and credentials
 * exist, refuses at construction with the exact dependency list. That is
 * deliberate: a provider that silently degraded to local keys in staging is the
 * failure mode §21 and §61 forbid, and a stub that returned fake keys would be
 * worse than one that refuses.
 *
 * <h2>What closing Gate 1 requires</h2>
 * <pre>
 * 1. software.amazon.awssdk:kms on the classpath
 * 2. a symmetric customer-managed key (its ARN in engineering.crypto.aws-kms.key-arn)
 * 3. credentials carrying exactly kms:GenerateDataKey, kms:Decrypt, kms:DescribeKey
 *    on that key — not kms:*, and no key-administration action
 * </pre>
 *
 * <h2>What it will do</h2>
 * <pre>
 * currentDataKey()  GenerateDataKey(KeySpec = AES_256, EncryptionContext = …)
 *                   → plaintext key (memory only) + CiphertextBlob to persist
 * unwrap()          Decrypt(CiphertextBlob, the same EncryptionContext)
 * </pre>
 *
 * <p>The encryption context is {@code purpose=engineering-anomaly-schema} plus the
 * environment, so a ciphertext cannot be decrypted under a different purpose or
 * environment. It carries no credential, customer or payment data, because an
 * encryption context is authenticated but <em>not</em> encrypted and appears in
 * CloudTrail.
 *
 * <p>The findings written by {@link LocalKeyProvider} stay tagged {@code LOCAL}
 * (see {@code key_provider}), so when this provider goes live the two are
 * distinguishable and development findings are never mistaken for production-valid
 * ones.
 */
public class AwsKmsKeyProvider implements EngineeringKeyProvider {

    public static final String PROVIDER_ID = "AWS_KMS";

    /** The message a misconfigured deployment gets, naming what is actually missing. */
    static final String NOT_PROVISIONED =
            "engineering.crypto.provider=aws-kms, but AWS KMS is not provisioned for this "
            + "application. Gate 1 is ON HOLD: the KMS SDK is not on the classpath, no "
            + "customer-managed key ARN is configured, and no credentials with "
            + "kms:GenerateDataKey / kms:Decrypt / kms:DescribeKey are available. "
            + "Provision those three, then implement this provider — do not fall back to the "
            + "local development provider outside a local environment.";

    public AwsKmsKeyProvider() {
        throw new UnsupportedOperationException(NOT_PROVISIONED);
    }

    @Override
    public String providerId() { return PROVIDER_ID; }

    @Override
    public DataKey currentDataKey() {
        throw new UnsupportedOperationException(NOT_PROVISIONED);
    }

    @Override
    public byte[] unwrap(String keyVersion, String encryptedDataKey) {
        throw new UnsupportedOperationException(NOT_PROVISIONED);
    }
}
