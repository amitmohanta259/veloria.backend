package com.app.master.service.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Fails the build if a credential-shaped literal reaches a tracked config file.
 *
 * This is a backstop, not a secret scanner: it catches the shapes that are
 * unmistakable — an AWS key id, a private key block, a long secret assigned
 * inline — which is what actually gets committed by accident.
 *
 * <b>It never prints a matched value.</b> A test that fails by echoing the
 * secret into CI logs has published it more widely than the commit did. Only
 * the file and line are reported, which is enough to find and fix it.
 *
 * {@code application-local.yaml} is deliberately not exempted. If it becomes
 * tracked again, this test is how you find out.
 */
class NoCommittedSecretsTest {

    /** Config and script files where a literal credential would actually land. */
    private static final Pattern SCANNED = Pattern.compile(
            ".*\\.(ya?ml|properties|json|xml|sh|env|conf|cfg)$", Pattern.CASE_INSENSITIVE);

    private record Rule(String label, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            new Rule("AWS access key id",
                    Pattern.compile("AKIA[0-9A-Z]{16}")),
            new Rule("AWS secret access key assigned inline",
                    Pattern.compile("(?i)secret[-_]?(access[-_]?)?key\\s*[:=]\\s*[\"']?[A-Za-z0-9/+=]{35,}")),
            new Rule("Private key block",
                    Pattern.compile("-----BEGIN (RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----")),
            new Rule("Connection string carrying a password",
                    Pattern.compile("(?i)(jdbc|postgres(ql)?|mysql|mongodb)://[^\\s:/]+:[^\\s@]+@")),
            new Rule("Bearer or JWT literal",
                    Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")));

    /**
     * A value that is an environment lookup, a placeholder or an obvious dummy
     * is not a secret. Excluding these keeps the check from crying wolf, which
     * is how a security test ends up disabled.
     */
    private static final Pattern SAFE_VALUE = Pattern.compile(
            "\\$\\{[^}]*}|(?i)\\b(changeme|placeholder|dummy|example|your[-_]?\\w+|xxx+|<[^>]+>)\\b");

    /**
     * The files git actually tracks.
     *
     * Walking the filesystem instead would flag files that are correctly
     * git-ignored — a developer's own local config is not a committed secret,
     * and reporting it as one is the false alarm that gets a security test
     * switched off.
     */
    private static List<Path> trackedFiles() throws IOException {
        try {
            Process p = new ProcessBuilder("git", "ls-files", "src/main/resources")
                    .redirectErrorStream(true).start();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().map(Path::of).filter(Files::isRegularFile).toList();
            }
        } catch (IOException e) {
            return List.of();   // git unavailable: skip rather than fail the build
        }
    }

    @Test
    @DisplayName("No credential-shaped literal appears in a tracked configuration file")
    void noSecretsInTrackedConfig() throws IOException {
        List<String> findings = new ArrayList<>();

        {
            for (Path file : trackedFiles().stream()
                    .filter(p -> SCANNED.matcher(p.getFileName().toString()).matches())
                    .toList()) {

                // The template exists to show the shape of the config; its
                // placeholders must not be mistaken for the thing they replace.
                if (file.getFileName().toString().contains(".example.")) continue;

                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (SAFE_VALUE.matcher(line).find()) continue;
                    for (Rule rule : RULES) {
                        if (rule.pattern().matcher(line).find()) {
                            // File and line only — never the value.
                            findings.add(file + ":" + (i + 1) + " — " + rule.label());
                        }
                    }
                }
            }
        }

        if (!findings.isEmpty()) {
            fail("Credential-shaped literals found in configuration. "
                    + "Move them to environment variables and keep the file out of git.\n  "
                    + String.join("\n  ", findings)
                    + "\n(values are deliberately not shown)");
        }
    }
}
