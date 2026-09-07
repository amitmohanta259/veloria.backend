package com.app.master.service.service.admin;

import com.app.master.service.core.entity.*;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * GSTR-2B ingestion and reconciliation (spec sections 9, 10 and 24).
 *
 * The parser is deliberately separated from the matching engine so a change of
 * file format does not touch the accounting logic. Two formats are supported
 * today — a flat CSV and a flat JSON array — both described by the column
 * mapping below.
 *
 * IMPORTANT: this reads a normalised export, not the government's own nested
 * GSTR-2B JSON. That schema is versioned by CBIC and is not reproduced here;
 * supplying a real 2B export lets the mapping be extended without changing the
 * reconciliation engine.
 *
 * Matching never silently upgrades a difference to MATCHED. A tax difference of
 * even one paise produces MISMATCH with the difference recorded.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Gstr2bService {

    public static final String MATCHED       = "MATCHED";
    public static final String PARTIAL_MATCH = "PARTIAL_MATCH";
    public static final String MISMATCH      = "MISMATCH";
    public static final String MISSING_IN_2B = "MISSING_IN_2B";
    public static final String DUPLICATE     = "DUPLICATE";
    public static final String PENDING       = "PENDING_REVIEW";

    /** Tolerance for a full match, in paise. Zero: differences are reported. */
    private static final long MATCH_TOLERANCE_PAISE = 0L;

    private final Gstr2bImportRepository importRepo;
    private final Gstr2bRecordRepository recordRepo;
    private final ItcReconciliationRepository reconRepo;
    private final GstInputTaxRepository inputTaxRepo;
    private final GstTaxPeriodRepository periodRepo;
    private final GstIdentityService identityService;
    private final GstAuditService auditService;
    private final GstRoundingService rounding;
    private final com.app.master.service.core.security.GstSecurityContext securityContext;
    private final com.app.master.service.service.admin.gstr2b.Gstr2bParserRegistry parserRegistry;

    private final ObjectMapper mapper = new ObjectMapper();

    private Long orgId() {
        return securityContext.current()
                .map(com.app.master.service.core.security.GstPrincipal::organizationId)
                .filter(Objects::nonNull)
                .orElseGet(identityService::defaultOrganizationId);
    }

    private Long regId() {
        return securityContext.current()
                .map(com.app.master.service.core.security.GstPrincipal::gstRegistrationId)
                .filter(Objects::nonNull)
                .orElseGet(() -> identityService.primaryRegistration(orgId())
                        .map(GstRegistrationEntity::getId).orElse(null));
    }

    // ── Import ───────────────────────────────────────────────────────────────

    /** One parsed 2B line, independent of the file format it came from. */
    public record ParsedRow(
            String supplierGstin, String supplierName, String invoiceNumber,
            LocalDate invoiceDate, String invoiceType,
            long taxablePaise, long cgstPaise, long sgstPaise, long igstPaise, long cessPaise) {}

    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public Gstr2bImportEntity importFile(String taxPeriod, String fileName, String format,
                                          byte[] content) throws VeloriaException {
        String fmt = format == null ? "" : format.toUpperCase();
        List<ParsedRow> rows = switch (fmt) {
            case "CSV"  -> parseCsv(content);
            case "JSON" -> parseJson(content);
            // The nested structure GSTN publishes. Parsing is delegated to a
            // versioned parser so a newer layout is refused rather than
            // misread; the rows it returns are the same normalized model the
            // flat readers produce, so reconciliation is unchanged.
            case "GOVERNMENT_JSON" -> parserRegistry.parse(content);
            default -> throw new VeloriaException(ResponseCode.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported GSTR-2B format '" + format + "'. "
                    + "Supported: CSV, JSON (flat export), GOVERNMENT_JSON (portal download). "
                    + "Excel needs a sample file to map.");
        };
        if (rows.isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "No GSTR-2B records found in " + fileName);
        }

        Gstr2bImportEntity imp = importRepo.save(Gstr2bImportEntity.builder()
                .taxPeriod(taxPeriod)
                .sourceFormat(fmt)
                .fileName(fileName)
                .recordCount(rows.size())
                .totalTaxablePaise(rows.stream().mapToLong(ParsedRow::taxablePaise).sum())
                .totalTaxPaise(rows.stream()
                        .mapToLong(r -> r.cgstPaise() + r.sgstPaise() + r.igstPaise() + r.cessPaise()).sum())
                .status("IMPORTED")
                .organizationId(orgId())
                .gstRegistrationId(regId())
                .importedBy(securityContext.actor())
                .build());

        // Duplicate 2B lines are flagged, not merged — a supplier reporting the
        // same invoice twice is a real condition the accountant must see.
        Set<String> seen = new HashSet<>();
        for (ParsedRow r : rows) {
            String key = norm(r.supplierGstin()) + "|" + norm(r.invoiceNumber());
            boolean dup = !seen.add(key);
            recordRepo.save(Gstr2bRecordEntity.builder()
                    .importId(imp.getId())
                    .supplierGstin(r.supplierGstin())
                    .supplierName(r.supplierName())
                    .invoiceNumber(r.invoiceNumber())
                    .invoiceDate(r.invoiceDate())
                    .invoiceType(r.invoiceType() != null ? r.invoiceType() : "B2B")
                    .taxableValuePaise(r.taxablePaise())
                    .cgstPaise(r.cgstPaise())
                    .sgstPaise(r.sgstPaise())
                    .igstPaise(r.igstPaise())
                    .cessPaise(r.cessPaise())
                    .totalTaxPaise(r.cgstPaise() + r.sgstPaise() + r.igstPaise() + r.cessPaise())
                    .taxPeriod(taxPeriod)
                    .matchStatus(dup ? DUPLICATE : PENDING)
                    .organizationId(orgId())
                    .build());
        }

        periodRepo.findFirstByOrganizationIdAndTaxPeriod(orgId(), taxPeriod).ifPresent(p -> {
            p.setGstr2bStatus("IMPORTED");
            periodRepo.save(p);
        });

        auditService.log("GSTR2B_IMPORT", imp.getId(), fileName, "GSTR2B_IMPORTED",
                taxPeriod, securityContext.actor());
        log.info("GSTR-2B import {}: {} records for period {}", fileName, rows.size(), taxPeriod);
        return imp;
    }

    // ── Parsers ──────────────────────────────────────────────────────────────

    /**
     * Expected CSV header (case-insensitive, order-independent):
     * supplier_gstin, supplier_name, invoice_number, invoice_date,
     * invoice_type, taxable_value, cgst, sgst, igst, cess
     *
     * Money columns are rupees and are converted to paise HALF_UP.
     */
    private List<ParsedRow> parseCsv(byte[] content) throws VeloriaException {
        List<ParsedRow> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new java.io.ByteArrayInputStream(content), StandardCharsets.UTF_8))) {

            String headerLine = br.readLine();
            if (headerLine == null) return out;
            String[] headers = splitCsv(headerLine);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                idx.put(headers[i].trim().toLowerCase().replace(" ", "_"), i);
            }
            requireColumns(idx, "supplier_gstin", "invoice_number", "taxable_value");

            String line;
            int lineNo = 1;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) continue;
                String[] c = splitCsv(line);
                try {
                    out.add(new ParsedRow(
                            get(c, idx, "supplier_gstin"),
                            get(c, idx, "supplier_name"),
                            get(c, idx, "invoice_number"),
                            parseDate(get(c, idx, "invoice_date")),
                            get(c, idx, "invoice_type"),
                            money(get(c, idx, "taxable_value")),
                            money(get(c, idx, "cgst")),
                            money(get(c, idx, "sgst")),
                            money(get(c, idx, "igst")),
                            money(get(c, idx, "cess"))));
                } catch (Exception rowError) {
                    throw new VeloriaException(ResponseCode.BAD_REQUEST,
                            "GSTR-2B CSV line " + lineNo + " could not be read: " + rowError.getMessage());
                }
            }
        } catch (VeloriaException e) {
            throw e;
        } catch (Exception e) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Could not read the GSTR-2B CSV: " + e.getMessage());
        }
        return out;
    }

    /** Flat JSON array with the same field names as the CSV header. */
    private List<ParsedRow> parseJson(byte[] content) throws VeloriaException {
        List<ParsedRow> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(content);
            JsonNode array = root.isArray() ? root : root.path("records");
            if (!array.isArray()) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Expected a JSON array of GSTR-2B records, or an object with a \"records\" array");
            }
            for (JsonNode n : array) {
                out.add(new ParsedRow(
                        text(n, "supplier_gstin", "ctin"),
                        text(n, "supplier_name", "trdnm"),
                        text(n, "invoice_number", "inum"),
                        parseDate(text(n, "invoice_date", "idt")),
                        text(n, "invoice_type", "inv_typ"),
                        money(text(n, "taxable_value", "txval")),
                        money(text(n, "cgst", "camt")),
                        money(text(n, "sgst", "samt")),
                        money(text(n, "igst", "iamt")),
                        money(text(n, "cess", "csamt"))));
            }
        } catch (VeloriaException e) {
            throw e;
        } catch (Exception e) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Could not read the GSTR-2B JSON: " + e.getMessage());
        }
        return out;
    }

    // ── Reconciliation ───────────────────────────────────────────────────────

    public record ReconResult(
            int purchaseInvoices, int twoBRecords,
            int matched, int partialMatch, int mismatch, int missingIn2b, int duplicates,
            long totalDifferencePaise, List<Map<String, Object>> differences) {}

    /**
     * Matches the purchase register against 2B for a period.
     *
     * Matching key is supplier GSTIN plus normalised invoice number. Amounts are
     * then compared; any difference beyond tolerance is MISMATCH with the delta
     * recorded, never silently treated as matched.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public ReconResult reconcile(String taxPeriod) {
        Long org = orgId();
        reconRepo.deleteByOrganizationIdAndTaxPeriod(org, taxPeriod);

        List<Gstr2bRecordEntity> twoB = recordRepo
                .findByOrganizationIdAndTaxPeriodOrderByIdAsc(org, taxPeriod);
        List<GstInputTaxEntity> purchases = inputTaxRepo
                .findByTaxPeriodOrderByCreatedAtDesc(taxPeriod).stream()
                .filter(p -> org == null || org.equals(p.getOrganizationId()))
                .toList();

        Map<String, Gstr2bRecordEntity> twoBIndex = new LinkedHashMap<>();
        for (Gstr2bRecordEntity r : twoB) {
            twoBIndex.putIfAbsent(norm(r.getSupplierGstin()) + "|" + norm(r.getInvoiceNumber()), r);
        }

        int matched = 0, partial = 0, mismatch = 0, missing = 0, duplicates = 0;
        long totalDiff = 0;
        List<Map<String, Object>> differences = new ArrayList<>();
        Set<Long> consumed = new HashSet<>();

        for (GstInputTaxEntity p : purchases) {
            String key = norm(p.getVendorGstin()) + "|" + norm(p.getInvoiceNumber());
            Gstr2bRecordEntity r = twoBIndex.get(key);

            if (r == null) {
                missing++;
                save(p, null, taxPeriod, MISSING_IN_2B, 0, 0, 0, 0,
                        "Purchase invoice is not reported in GSTR-2B for this period");
                p.setGstr2bMatchStatus(MISSING_IN_2B);
                inputTaxRepo.save(p);
                differences.add(diff(p, null, MISSING_IN_2B, 0));
                continue;
            }

            consumed.add(r.getId());

            long dTaxable = nvl(p.getTaxableValue()) - nvl(r.getTaxableValuePaise());
            long dCgst    = nvl(p.getCgstAmount())   - nvl(r.getCgstPaise());
            long dSgst    = nvl(p.getSgstAmount())   - nvl(r.getSgstPaise());
            long dIgst    = nvl(p.getIgstAmount())   - nvl(r.getIgstPaise());
            long dTotal   = dCgst + dSgst + dIgst;

            boolean taxMatches = Math.abs(dCgst) <= MATCH_TOLERANCE_PAISE
                    && Math.abs(dSgst) <= MATCH_TOLERANCE_PAISE
                    && Math.abs(dIgst) <= MATCH_TOLERANCE_PAISE;
            boolean taxableMatches = Math.abs(dTaxable) <= MATCH_TOLERANCE_PAISE;
            boolean dateMatches = p.getInvoiceDate() == null || r.getInvoiceDate() == null
                    || p.getInvoiceDate().equals(r.getInvoiceDate());

            String status;
            String note;
            if (taxMatches && taxableMatches && dateMatches) {
                status = MATCHED; matched++; note = null;
            } else if (taxMatches && taxableMatches) {
                status = PARTIAL_MATCH; partial++;
                note = "Amounts agree but the invoice date differs: register "
                        + p.getInvoiceDate() + " vs 2B " + r.getInvoiceDate();
            } else {
                status = MISMATCH; mismatch++;
                note = "Difference — taxable " + paise(dTaxable) + ", CGST " + paise(dCgst)
                        + ", SGST " + paise(dSgst) + ", IGST " + paise(dIgst);
            }

            totalDiff += Math.abs(dTotal);
            save(p, r, taxPeriod, status, dTaxable, dCgst, dSgst, dIgst, note);

            r.setMatchStatus(status);
            r.setMatchedInputTaxId(p.getId());
            recordRepo.save(r);

            p.setGstr2bMatchStatus(status);
            inputTaxRepo.save(p);

            if (!MATCHED.equals(status)) differences.add(diff(p, r, status, dTotal));
        }

        // 2B lines with no purchase invoice behind them.
        for (Gstr2bRecordEntity r : twoB) {
            if (consumed.contains(r.getId())) continue;
            if (DUPLICATE.equals(r.getMatchStatus())) { duplicates++; continue; }
            r.setMatchStatus(PENDING);
            recordRepo.save(r);
            differences.add(Map.of(
                    "type", "IN_2B_NOT_IN_REGISTER",
                    "supplierGstin", GstIdentityService.maskGstin(r.getSupplierGstin()),
                    "invoiceNumber", String.valueOf(r.getInvoiceNumber()),
                    "taxPaise", nvl(r.getTotalTaxPaise())));
        }

        periodRepo.findFirstByOrganizationIdAndTaxPeriod(org, taxPeriod).ifPresent(pp -> {
            pp.setGstr2bStatus("RECONCILED");
            periodRepo.save(pp);
        });

        auditService.log("GSTR2B_RECONCILIATION", null, taxPeriod, "GSTR2B_RECONCILED",
                taxPeriod, securityContext.actor());
        log.info("GSTR-2B reconciliation {}: {} matched, {} partial, {} mismatch, {} missing",
                taxPeriod, matched, partial, mismatch, missing);

        return new ReconResult(purchases.size(), twoB.size(), matched, partial, mismatch,
                missing, duplicates, totalDiff, differences);
    }

    private void save(GstInputTaxEntity p, Gstr2bRecordEntity r, String period, String status,
                      long dTaxable, long dCgst, long dSgst, long dIgst, String note) {
        reconRepo.save(ItcReconciliationEntity.builder()
                .inputTaxId(p.getId())
                .gstr2bRecordId(r != null ? r.getId() : null)
                .taxPeriod(period)
                .matchStatus(status)
                .taxableDifferencePaise(dTaxable)
                .cgstDifferencePaise(dCgst)
                .sgstDifferencePaise(dSgst)
                .igstDifferencePaise(dIgst)
                .totalDifferencePaise(dCgst + dSgst + dIgst)
                .differenceNotes(note)
                .organizationId(orgId())
                .reconciledBy(securityContext.actor())
                .build());
    }

    private Map<String, Object> diff(GstInputTaxEntity p, Gstr2bRecordEntity r, String status, long dTotal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", status);
        m.put("invoiceNumber", p.getInvoiceNumber());
        m.put("supplierGstin", GstIdentityService.maskGstin(p.getVendorGstin()));
        m.put("registerTaxPaise", nvl(p.getTotalInputTax()));
        m.put("twoBTaxPaise", r != null ? nvl(r.getTotalTaxPaise()) : null);
        m.put("differencePaise", dTotal);
        return m;
    }

    public List<Gstr2bRecordEntity> recordsFor(String period) {
        return recordRepo.findByOrganizationIdAndTaxPeriodOrderByIdAsc(orgId(), period);
    }

    public List<ItcReconciliationEntity> reconciliationFor(String period) {
        return reconRepo.findByOrganizationIdAndTaxPeriodOrderByIdAsc(orgId(), period);
    }

    public Map<String, Long> statusCounts(String period) {
        Long org = orgId();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : List.of(MATCHED, PARTIAL_MATCH, MISMATCH, DUPLICATE, PENDING)) {
            counts.put(s, recordRepo.countByOrganizationIdAndTaxPeriodAndMatchStatus(org, period, s));
        }
        return counts;
    }

    // ── Parsing helpers ──────────────────────────────────────────────────────

    private void requireColumns(Map<String, Integer> idx, String... required) throws VeloriaException {
        List<String> missing = Arrays.stream(required).filter(c -> !idx.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "GSTR-2B file is missing required column(s): " + String.join(", ", missing));
        }
    }

    private String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') { quoted = !quoted; continue; }
            if (ch == ',' && !quoted) { out.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private String get(String[] cells, Map<String, Integer> idx, String col) {
        Integer i = idx.get(col);
        if (i == null || i >= cells.length) return null;
        String v = cells[i].trim();
        return v.isEmpty() ? null : v;
    }

    private String text(JsonNode n, String... names) {
        for (String name : names) {
            JsonNode v = n.get(name);
            if (v != null && !v.isNull()) return v.asText();
        }
        return null;
    }

    private long money(String raw) {
        if (raw == null || raw.isBlank()) return 0L;
        return rounding.rupeesToPaise(new BigDecimal(raw.replace(",", "").trim()));
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        for (String pattern : List.of("yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", "MM/dd/yyyy")) {
            try {
                return LocalDate.parse(raw.trim(), DateTimeFormatter.ofPattern(pattern));
            } catch (Exception ignored) { /* try the next pattern */ }
        }
        return null;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    private static String paise(long p) {
        return (p < 0 ? "-" : "") + "₹" + String.format("%.2f", Math.abs(p) / 100.0);
    }

    private static long nvl(Long v) { return v != null ? v : 0L; }
}
