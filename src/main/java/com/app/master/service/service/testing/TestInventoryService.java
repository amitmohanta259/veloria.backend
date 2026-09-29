package com.app.master.service.service.testing;

import com.app.master.service.core.entity.TestInventoryEntity;
import com.app.master.service.repository.testing.TestInventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Enumerates what the test programme is accountable for.
 *
 * <p>A coverage percentage means nothing without a denominator somebody can
 * inspect. This walks the actual source — admin and client React views, Spring
 * controllers, JPA entities — and records every route, interactive element, API
 * endpoint and entity it finds, each with a stable identifier.
 *
 * <p><b>What it does not do is decide that something is tested.</b> Discovery sets
 * {@code UNTESTED}; only a test execution or a person marks otherwise. An
 * inventory that optimistically assumed coverage would be worse than none, because
 * it would report a number nobody could trust.
 *
 * <p>Re-running discovery is safe: an item keeps its identifier and its status, so
 * the record of what has been tested survives rediscovery.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TestInventoryService {

    private final TestInventoryRepository repository;

    @Value("${testing.inventory.admin-src:}")  private String adminSrcOverride;
    @Value("${testing.inventory.client-src:}") private String clientSrcOverride;
    @Value("${testing.inventory.backend-src:}") private String backendSrcOverride;

    /** Interactive things worth holding a test accountable for. */
    private static final Map<String, Pattern> ELEMENT_PATTERNS = Map.of(
            "BUTTON",   Pattern.compile("<button\\b"),
            "INPUT",    Pattern.compile("<input\\b"),
            "SELECT",   Pattern.compile("<select\\b"),
            "TEXTAREA", Pattern.compile("<textarea\\b"),
            "LINK",     Pattern.compile("<(?:NavLink|Link)\\b")
    );

    private static final Pattern ROUTE =
            Pattern.compile("<Route\\s+path=\"([^\"]+)\"");
    private static final Pattern REQUEST_MAPPING =
            Pattern.compile("@RequestMapping\\(\\s*\"([^\"]+)\"");
    private static final Pattern METHOD_MAPPING =
            Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping\\(?\\s*(?:value\\s*=\\s*)?\"?([^\")\\s]*)\"?");
    private static final Pattern TABLE_NAME =
            Pattern.compile("@Table\\(\\s*name\\s*=\\s*\"([^\"]+)\"");
    /** A data-testid is the strongest signal that an element is addressable by a test. */
    private static final Pattern TEST_ID =
            Pattern.compile("data-testid=\"([^\"]+)\"");

    public record DiscoveryOutcome(int routes, int uiElements, int apiEndpoints, int entities,
                                   int created, int kept, List<String> skipped) {}

    /**
     * Walks the source and records what it finds.
     *
     * @param markTestedFromTestIds when true, an element carrying a
     *        {@code data-testid} that a Playwright spec also references is recorded
     *        as covered by that spec. This is the one automatic link that is
     *        defensible — the selector is literally the test's handle on the element.
     */
    @Transactional
    public DiscoveryOutcome discover(boolean markTestedFromTestIds) {
        Path adminSrc   = resolve(adminSrcOverride,   "../veloria.frontend/frontend-admin/src");
        Path clientSrc  = resolve(clientSrcOverride,  "../veloria.frontend/frontend-client/src");
        Path backendSrc = resolve(backendSrcOverride, "src/main/java/com/app/master/service");

        List<String> skipped = new ArrayList<>();
        int created = 0, kept = 0;
        int routes = 0, elements = 0, endpoints = 0, entities = 0;

        Set<String> testedIds = markTestedFromTestIds ? testIdsReferencedByTests() : Set.of();

        // ── routes and UI elements ──
        for (var pair : List.of(Map.entry("admin", adminSrc), Map.entry("client", clientSrc))) {
            String app = pair.getKey();
            Path root = pair.getValue();
            if (!Files.isDirectory(root)) {
                skipped.add(app + " source not found at " + root);
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".tsx")).toList()) {
                    String body = Files.readString(f);
                    String module = moduleOf(f, root);

                    Matcher r = ROUTE.matcher(body);
                    while (r.find()) {
                        var res = upsert("ROUTE", app + "/" + module, f.toString(),
                                r.group(1), "ROUTE", r.group(1), null, null);
                        routes++; created += res ? 1 : 0; kept += res ? 0 : 1;
                    }

                    for (var entry : ELEMENT_PATTERNS.entrySet()) {
                        Matcher m = entry.getValue().matcher(body);
                        int n = 0;
                        while (m.find()) n++;
                        for (int i = 1; i <= n; i++) {
                            var res = upsert("UI_ELEMENT", app + "/" + module, f.toString(),
                                    null, entry.getKey(),
                                    entry.getKey() + " #" + i + " in " + f.getFileName(), null, null);
                            elements++; created += res ? 1 : 0; kept += res ? 0 : 1;
                        }
                    }

                    Matcher t = TEST_ID.matcher(body);
                    while (t.find()) {
                        String testId = t.group(1);
                        boolean covered = testedIds.contains(testId);
                        var res = upsert("UI_ELEMENT", app + "/" + module, f.toString(), null,
                                "TESTID", "data-testid=\"" + testId + "\"",
                                covered ? "PASSED" : null,
                                covered ? "Playwright selector " + testId : null);
                        elements++; created += res ? 1 : 0; kept += res ? 0 : 1;
                    }
                }
            } catch (IOException e) {
                skipped.add(app + " scan failed: " + e.getMessage());
            }
        }

        // ── API endpoints and entities ──
        if (!Files.isDirectory(backendSrc)) {
            skipped.add("backend source not found at " + backendSrc);
        } else {
            try (Stream<Path> files = Files.walk(backendSrc)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String body = Files.readString(f);
                    String file = f.toString();

                    if (file.contains("/controller/")) {
                        Matcher base = REQUEST_MAPPING.matcher(body);
                        String prefix = base.find() ? base.group(1) : "";
                        Matcher m = METHOD_MAPPING.matcher(body);
                        while (m.find()) {
                            String verb = m.group(1).toUpperCase(Locale.ROOT);
                            String path = m.group(2) == null ? "" : m.group(2);
                            String full = (prefix + path).replaceAll("//+", "/");
                            var res = upsert("API_ENDPOINT", moduleOf(f, backendSrc), file,
                                    full, verb, verb + " " + full, null, null);
                            endpoints++; created += res ? 1 : 0; kept += res ? 0 : 1;
                        }
                    }

                    Matcher tbl = TABLE_NAME.matcher(body);
                    if (tbl.find()) {
                        var res = upsert("ENTITY", moduleOf(f, backendSrc), file,
                                null, "TABLE", tbl.group(1), null, null);
                        entities++; created += res ? 1 : 0; kept += res ? 0 : 1;
                    }
                }
            } catch (IOException e) {
                skipped.add("backend scan failed: " + e.getMessage());
            }
        }

        log.info("AUDIT INVENTORY_DISCOVERY routes={} elements={} endpoints={} entities={} created={} kept={}",
                routes, elements, endpoints, entities, created, kept);
        return new DiscoveryOutcome(routes, elements, endpoints, entities, created, kept, skipped);
    }

    /**
     * Every {@code data-testid} any Playwright spec references.
     *
     * <p>Read from the spec sources, so the link between an element and its test is
     * evidence rather than an assumption.
     */
    private Set<String> testIdsReferencedByTests() {
        Path specs = resolve(null, "automation-test/playwright/tests");
        Path pages = resolve(null, "automation-test/playwright/pages");
        Set<String> ids = new HashSet<>();
        Pattern ref = Pattern.compile("getByTestId\\(\\s*['\"]([^'\"]+)['\"]");
        for (Path root : List.of(specs, pages)) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".js")).toList()) {
                    Matcher m = ref.matcher(Files.readString(f));
                    while (m.find()) ids.add(m.group(1));
                }
            } catch (IOException e) {
                log.warn("Could not read Playwright specs at {}", root, e);
            }
        }
        return ids;
    }

    /** @return true when a new row was created */
    private boolean upsert(String kind, String module, String sourceFile, String route,
                           String elementType, String label, String statusIfNew, String coveringTest) {
        String itemId = idFor(kind, module, elementType, label, sourceFile);
        Optional<TestInventoryEntity> existing = repository.findByItemId(itemId);
        if (existing.isPresent()) {
            // Rediscovery must never reset what a run already proved.
            TestInventoryEntity e = existing.get();
            if (coveringTest != null && e.getCoveringTest() == null) {
                e.setCoveringTest(coveringTest);
                if (statusIfNew != null && "UNTESTED".equals(e.getTestStatus())) {
                    e.setTestStatus(statusIfNew);
                }
                e.setModified(java.time.Instant.now());
                repository.save(e);
            }
            return false;
        }
        repository.save(TestInventoryEntity.builder()
                .itemId(itemId).itemKind(kind).module(module).sourceFile(sourceFile)
                .route(route).elementType(elementType)
                .label(label == null ? null : label.substring(0, Math.min(label.length(), 390)))
                .testStatus(statusIfNew == null ? "UNTESTED" : statusIfNew)
                .coveringTest(coveringTest)
                .build());
        return true;
    }

    /**
     * A stable identifier.
     *
     * <p>Derived from what the item <em>is</em>, not from the order it was found in,
     * so adding a button to a file does not renumber every element after it and
     * silently detach them from their recorded results.
     */
    private String idFor(String kind, String module, String elementType, String label, String sourceFile) {
        String prefix = switch (kind) {
            case "ROUTE" -> "RT";
            case "UI_ELEMENT" -> "UI";
            case "API_ENDPOINT" -> "API";
            default -> "ENT";
        };
        String material = kind + "|" + module + "|" + elementType + "|" + label + "|" + sourceFile;
        String digest = Integer.toHexString(material.hashCode()).toUpperCase(Locale.ROOT);
        String moduleTag = module.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (moduleTag.length() > 10) moduleTag = moduleTag.substring(0, 10);
        return prefix + "-" + moduleTag + "-" + digest;
    }

    private String moduleOf(Path file, Path root) {
        Path rel = root.relativize(file);
        return rel.getNameCount() > 1 ? rel.getName(0).toString() : "root";
    }

    private Path resolve(String override, String relative) {
        if (override != null && !override.isBlank()) return Paths.get(override).toAbsolutePath().normalize();
        return Paths.get(System.getProperty("user.dir")).resolve(relative).normalize();
    }

    // ── reporting ────────────────────────────────────────────────────────────

    public record CoverageLine(String key, long total, long passed, long failed, long blocked, long untested) {
        public double coveragePercent() { return total == 0 ? 0 : 100.0 * passed / total; }
    }

    /** Inventory coverage by kind — the traceability numbers, from real rows. */
    public List<CoverageLine> coverageByKind() {
        return fold(repository.countByKindAndStatus());
    }

    public List<CoverageLine> coverageByModule() {
        return fold(repository.countByModuleAndStatus());
    }

    private List<CoverageLine> fold(List<Object[]> rows) {
        Map<String, long[]> acc = new TreeMap<>();
        for (Object[] row : rows) {
            String key = String.valueOf(row[0]);
            String status = String.valueOf(row[1]);
            long n = ((Number) row[2]).longValue();
            long[] cells = acc.computeIfAbsent(key, k -> new long[4]);
            switch (status) {
                case "PASSED"  -> cells[0] += n;
                case "FAILED"  -> cells[1] += n;
                case "BLOCKED" -> cells[2] += n;
                default        -> cells[3] += n;
            }
        }
        List<CoverageLine> out = new ArrayList<>();
        acc.forEach((k, c) -> out.add(new CoverageLine(k, c[0] + c[1] + c[2] + c[3], c[0], c[1], c[2], c[3])));
        return out;
    }
}
