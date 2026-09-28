package com.app.master.service.service.engineering;

import com.app.master.service.core.engineering.EngineeringKeyProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Chooses the key provider from configuration, and refuses unsafe combinations.
 *
 * <p>The provider is never hardcoded (§12): {@code engineering.crypto.provider}
 * selects it. The important logic here is not the selection but the <b>refusal</b> —
 * §21 and §61 require that a non-local environment configured with the local
 * provider fails at startup rather than quietly encrypting production findings with
 * a key from somebody's home directory.
 *
 * <p>Failing at startup is the point. A runtime check would let the application
 * serve traffic and only break when the first anomaly was written, by which time
 * the misconfiguration has already been deployed.
 */
@Configuration
@Slf4j
public class EngineeringCryptoConfig {

    public static final String LOCAL = "local";
    public static final String AWS_KMS = "aws-kms";

    /** Profiles in which the development key provider is permitted. */
    private static final List<String> LOCAL_PROFILES = List.of("local", "test", "default");

    @Bean
    public EngineeringKeyProvider engineeringKeyProvider(
            Environment environment,
            @Value("${engineering.crypto.provider:local}") String provider,
            @Value("${engineering.crypto.local.key-directory:}") String keyDirectory,
            @Value("${engineering.crypto.local.require-secure-permissions:true}") boolean requireSecurePermissions
    ) throws IOException {

        String selected = provider == null ? LOCAL : provider.trim().toLowerCase(Locale.ROOT);

        if (AWS_KMS.equals(selected)) {
            // Constructing it is the check: it refuses with the dependency list
            // until Gate 1 is closed. No silent downgrade to local.
            return new AwsKmsKeyProvider();
        }

        if (!LOCAL.equals(selected)) {
            throw new IllegalStateException("Unknown engineering.crypto.provider '" + provider
                    + "'. Supported values are 'local' (development only) and 'aws-kms'.");
        }

        requireLocalEnvironment(environment);

        Path directory = resolveKeyDirectory(keyDirectory);
        requireOutsideRepository(directory);

        log.warn("Engineering schema encryption is using the LOCAL DEVELOPMENT key provider. "
                + "This is not production safe, not staging safe, and does not close the AWS KMS gate.");
        return new LocalKeyProvider(directory, requireSecurePermissions);
    }

    /** §21 — the local provider outside a local environment is a startup failure. */
    private void requireLocalEnvironment(Environment environment) {
        String[] active = environment.getActiveProfiles();
        List<String> profiles = active.length == 0 ? List.of("default") : Arrays.asList(active);

        boolean localEnvironment = profiles.stream()
                .allMatch(p -> LOCAL_PROFILES.contains(p.trim().toLowerCase(Locale.ROOT)));

        if (!localEnvironment) {
            throw new IllegalStateException(
                    "engineering.crypto.provider=local is only permitted in a local development "
                    + "environment, but the active profiles are " + profiles + ". The local provider "
                    + "keeps its master key in a file on one machine, with no audit trail and no "
                    + "access control beyond filesystem permissions. Configure "
                    + "engineering.crypto.provider=aws-kms for this environment — and do not work "
                    + "around this by relabelling the profile.");
        }
    }

    /**
     * Defaults to {@code ~/.veloria/engineering-keys/} (§13).
     *
     * <p>Configurable so a developer can move it, but never defaulted into the
     * project tree.
     */
    private Path resolveKeyDirectory(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured.trim()).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".veloria", "engineering-keys")
                .toAbsolutePath().normalize();
    }

    /**
     * Refuses a key directory inside a checkout.
     *
     * <p>§13 forbids key material in {@code src/main/resources},
     * {@code src/test/resources}, the repository root or the frontend, and §67 makes
     * "local key storage is inside the repository" a stop condition. A
     * {@code .gitignore} entry is a second line of defence, not the first — so this
     * refuses the configuration outright rather than trusting the ignore rules.
     */
    private void requireOutsideRepository(Path directory) {
        Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();

        Path candidate = directory;
        while (candidate != null) {
            // A repository rooted at the home directory is not a project checkout —
            // it is the kind of dotfiles repo some people keep — and the documented
            // default deliberately lives under $HOME. Vetoing that would make the
            // default unusable. An earlier version tried to tell the two apart by
            // looking for a build file at the root, which failed the moment a stray
            // package.json turned up in the home directory.
            //
            // Whether $HOME is itself a repository is a separate risk, and one this
            // check cannot fix: it is handled by ignore rules, and verified in the
            // phase report.
            boolean isRepositoryRoot = java.nio.file.Files.isDirectory(candidate.resolve(".git"))
                    || java.nio.file.Files.isRegularFile(candidate.resolve(".git"));

            if (isRepositoryRoot && !candidate.equals(home)) {
                throw new IllegalStateException(
                        "engineering.crypto.local.key-directory resolves to " + directory
                        + ", which is inside the repository at " + candidate
                        + ". Key material must live outside every project checkout — the default is "
                        + "~/.veloria/engineering-keys/.");
            }
            candidate = candidate.getParent();
        }
    }
}
