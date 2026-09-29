package com.app.master.service.service.testing;

import com.app.master.service.core.entity.TestResultEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Turns each framework's own report into individual test results.
 *
 * <p>Parsing the report rather than scraping stdout matters: stdout tells you
 * "564 tests, 0 failures", the report tells you <em>which</em> test failed and
 * with what message. A dashboard that only knew the totals could not attach a
 * failure to a defect, which is the point of collecting them.
 *
 * <p>Every parser is defensive. A missing or malformed report yields an empty
 * list and a warning — never a fabricated pass.
 */
@Component
@Slf4j
public class TestReportParser {

    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param since when the step started. Report files older than this are left
     *        alone: Maven does not clear {@code target/surefire-reports} between
     *        runs, so a module-scoped run would otherwise pick up every stale XML
     *        from previous runs and report the whole estate as its own result — a
     *        nine-test run claiming 564 passes, which is exactly what it did before
     *        this filter existed.
     */
    public List<TestResultEntity> parse(String reportKind, String suite, Path workingDir,
                                        String stdout, java.time.Instant since) {
        try {
            return switch (reportKind) {
                case TestCommandCatalog.REPORT_SUREFIRE   -> surefire(suite, workingDir, since);
                case TestCommandCatalog.REPORT_CUCUMBER   -> cucumber(suite, workingDir, since);
                case TestCommandCatalog.REPORT_PLAYWRIGHT -> playwright(suite, stdout);
                default -> List.of();
            };
        } catch (Exception e) {
            log.warn("Could not parse the {} report for {}", reportKind, suite, e);
            return List.of();
        }
    }

    // ── Surefire XML ─────────────────────────────────────────────────────────

    private List<TestResultEntity> surefire(String suite, Path workingDir, java.time.Instant since) throws Exception {
        Path dir = workingDir.resolve("target/surefire-reports");
        if (!Files.isDirectory(dir)) {
            log.warn("No surefire reports at {}", dir);
            return List.of();
        }
        List<TestResultEntity> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> fresh = files
                    .filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .filter(p -> writtenSince(p, since))
                    .toList();
            if (fresh.isEmpty()) {
                log.warn("No surefire report in {} was written by this run", dir);
            }
            for (Path f : fresh) {
                var factory = DocumentBuilderFactory.newInstance();
                // The reports are ours, but an XML parser with entity resolution on
                // is an XXE sink regardless of who wrote the file.
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);

                var doc = factory.newDocumentBuilder().parse(f.toFile());
                var cases = doc.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    var node = (org.w3c.dom.Element) cases.item(i);
                    String className = node.getAttribute("classname");
                    String name = node.getAttribute("name");
                    String time = node.getAttribute("time");

                    String status = "PASSED";
                    String message = null;
                    if (node.getElementsByTagName("failure").getLength() > 0) {
                        status = "FAILED";
                        message = text(node, "failure");
                    } else if (node.getElementsByTagName("error").getLength() > 0) {
                        status = "FAILED";
                        message = text(node, "error");
                    } else if (node.getElementsByTagName("skipped").getLength() > 0) {
                        status = "SKIPPED";
                    }

                    out.add(TestResultEntity.builder()
                            .suite(suite)
                            .module(moduleOf(className))
                            .className(className)
                            .testName(name)
                            .status(status)
                            .durationMs(time.isBlank() ? null : (long) (Double.parseDouble(time) * 1000))
                            .failureMessage(trim(message))
                            .build());
                }
            }
        }
        return out;
    }

    private String text(org.w3c.dom.Element parent, String tag) {
        var nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        var el = (org.w3c.dom.Element) nodes.item(0);
        String attr = el.getAttribute("message");
        String body = el.getTextContent();
        return (attr == null || attr.isBlank()) ? body : attr + "\n" + body;
    }

    /** com.app.master.service.gst.FooTest → gst */
    private String moduleOf(String className) {
        if (className == null) return null;
        String prefix = "com.app.master.service.";
        if (!className.startsWith(prefix)) return null;
        String rest = className.substring(prefix.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? "root" : rest.substring(0, dot);
    }

    // ── Cucumber JSON ────────────────────────────────────────────────────────

    private List<TestResultEntity> cucumber(String suite, Path workingDir, java.time.Instant since) throws Exception {
        Path report = workingDir.resolve("target/cucumber.json");
        if (!Files.exists(report)) {
            log.warn("No cucumber report at {}", report);
            return List.of();
        }
        if (!writtenSince(report, since)) {
            log.warn("The cucumber report at {} predates this run", report);
            return List.of();
        }
        List<TestResultEntity> out = new ArrayList<>();
        JsonNode features = json.readTree(report.toFile());
        for (JsonNode feature : features) {
            String featureName = feature.path("name").asText("");
            String uri = feature.path("uri").asText("");
            for (JsonNode element : feature.path("elements")) {
                if (!"scenario".equals(element.path("type").asText())) continue;

                String status = "PASSED";
                StringBuilder failure = new StringBuilder();
                long duration = 0;
                for (JsonNode step : element.path("steps")) {
                    JsonNode result = step.path("result");
                    duration += result.path("duration").asLong(0) / 1_000_000;
                    String s = result.path("status").asText("passed");
                    if ("failed".equals(s)) {
                        status = "FAILED";
                        failure.append(result.path("error_message").asText("")).append('\n');
                    } else if (("skipped".equals(s) || "undefined".equals(s)) && "PASSED".equals(status)) {
                        status = "SKIPPED";
                    }
                }
                out.add(TestResultEntity.builder()
                        .suite(suite)
                        .module(moduleFromUri(uri))
                        .className(featureName)
                        .testName(element.path("name").asText(""))
                        .status(status)
                        .durationMs(duration)
                        .failureMessage(trim(failure.length() == 0 ? null : failure.toString()))
                        .build());
            }
        }
        return out;
    }

    /** features/gst/gst_rules.feature → gst */
    private String moduleFromUri(String uri) {
        if (uri == null) return null;
        String[] parts = uri.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("features".equals(parts[i])) return parts[i + 1];
        }
        return null;
    }

    // ── Playwright JSON (on stdout) ──────────────────────────────────────────

    private List<TestResultEntity> playwright(String suite, String stdout) throws Exception {
        if (stdout == null || stdout.isBlank()) return List.of();
        // The JSON reporter writes the document to stdout, but a dev-server banner
        // or a global-setup line can precede it, so start at the first brace.
        int start = stdout.indexOf('{');
        if (start < 0) return List.of();

        JsonNode root = json.readTree(stdout.substring(start));
        List<TestResultEntity> out = new ArrayList<>();
        collectSpecs(root.path("suites"), suite, "", out);
        return out;
    }

    private void collectSpecs(JsonNode suites, String suiteName, String path, List<TestResultEntity> out) {
        for (JsonNode s : suites) {
            String title = s.path("title").asText("");
            String here = path.isEmpty() ? title : path + " › " + title;
            for (JsonNode spec : s.path("specs")) {
                String testTitle = spec.path("title").asText("");
                for (JsonNode test : spec.path("tests")) {
                    JsonNode last = test.path("results").isArray() && test.path("results").size() > 0
                            ? test.path("results").get(test.path("results").size() - 1)
                            : null;
                    String raw = last == null ? test.path("status").asText("") : last.path("status").asText("");
                    String status = switch (raw) {
                        case "passed", "expected" -> "PASSED";
                        case "skipped" -> "SKIPPED";
                        case "" -> "SKIPPED";
                        default -> "FAILED";
                    };
                    String message = last == null ? null : last.path("error").path("message").asText(null);
                    out.add(TestResultEntity.builder()
                            .suite(suiteName)
                            .module(moduleFromSpecPath(here))
                            .className(here)
                            .testName(testTitle)
                            .status(status)
                            .durationMs(last == null ? null : last.path("duration").asLong(0))
                            .failureMessage(trim(message))
                            .build());
                }
            }
            if (s.has("suites")) collectSpecs(s.path("suites"), suiteName, here, out);
        }
    }

    private String moduleFromSpecPath(String path) {
        if (path == null) return null;
        String lower = path.toLowerCase();
        for (String m : List.of("engineering", "checkout", "gst", "order", "returns",
                                "roles", "inventory", "products", "authentication", "compliance")) {
            if (lower.contains(m)) return m;
        }
        return null;
    }

    /** Whether a report file was written by the step that just ran, not a previous one. */
    private boolean writtenSince(Path file, java.time.Instant since) {
        if (since == null) return true;
        try {
            // A second of slack: some filesystems round modification times down.
            return !Files.getLastModifiedTime(file).toInstant().isBefore(since.minusSeconds(1));
        } catch (IOException e) {
            log.warn("Could not read the modification time of {}", file, e);
            return false;
        }
    }

    /** Keeps a failure message useful without letting a stack trace fill the column. */
    private String trim(String message) {
        if (message == null) return null;
        String clean = message.strip();
        return clean.length() <= 4000 ? clean : clean.substring(0, 4000) + "\n… truncated";
    }
}
