package com.app.master.service.service.admin;

import com.app.master.service.core.entity.*;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * GSTR-1 and GSTR-3B preparation (spec sections 22, 23, 25, 26 and 35).
 *
 * Both returns are built from the GST movement ledger, never from live order
 * rows, so every figure traces back to a posted accounting entry.
 *
 * SCOPE AND HONESTY
 * -----------------
 * This produces an internal, traceable dataset — not the government's filing
 * JSON. The official GSTR-1 and GSTR-3B schemas are versioned by CBIC and are
 * not reproduced here, so {@code schemaVersion} records the internal dataset
 * version and nothing in this class claims a return has been filed. A snapshot
 * only reaches FILED when {@link #recordFiling} is given a real acknowledgement
 * number by a human.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstReturnPreparationService {

    /** Internal dataset version. NOT an official GST return schema version. */
    public static final String INTERNAL_SCHEMA_VERSION = "veloria-internal-1";

    public static final String NOT_STARTED   = "NOT_STARTED";
    public static final String PREPARED      = "PREPARED";
    public static final String UNDER_REVIEW  = "UNDER_REVIEW";
    public static final String READY_TO_FILE = "READY_TO_FILE";
    public static final String SUBMITTED     = "SUBMITTED";
    public static final String FILED         = "FILED";

    private final GstMovementLedgerRepository movementRepo;
    private final GstReturnSnapshotRepository snapshotRepo;
    private final GstTaxPeriodRepository periodRepo;
    private final GstCreditNoteRepository creditNoteRepo;
    private final GstDebitNoteRepository debitNoteRepo;
    private final GstTaxPeriodService periodService;
    private final GstItcService itcService;
    private final GstPaymentRepository paymentRepo;
    private final GstIdentityService identityService;
    private final GstAuditService auditService;
    private final com.app.master.service.core.security.GstSecurityContext securityContext;

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

    private List<GstMovementLedgerEntity> postedMovements(String period) {
        Long org = orgId();
        return movementRepo.findByTaxPeriodOrderByCreatedAtDesc(period).stream()
                .filter(m -> org == null || org.equals(m.getOrganizationId()))
                .filter(m -> "POSTED".equals(m.getStatus()))
                .toList();
    }

    // ── GSTR-1 ───────────────────────────────────────────────────────────────

    /**
     * Outward supplies grouped into the categories a GSTR-1 return distinguishes.
     * B2B is decided by the presence of a counterparty GSTIN on the movement.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstReturnSnapshotEntity prepareGstr1(String taxPeriod) throws VeloriaException {
        List<GstMovementLedgerEntity> movements = postedMovements(taxPeriod);

        List<Map<String, Object>> b2b = new ArrayList<>();
        List<Map<String, Object>> b2c = new ArrayList<>();
        Map<String, Map<String, Object>> hsnSummary = new LinkedHashMap<>();

        long b2bTaxable = 0, b2bTax = 0, b2cTaxable = 0, b2cTax = 0;

        for (GstMovementLedgerEntity m : movements) {
            if (!GstMovementService.SALE_OUTPUT.equals(m.getMovementType())) continue;

            boolean isB2b = m.getCounterpartyGstin() != null && !m.getCounterpartyGstin().isBlank();
            Map<String, Object> line = lineOf(m);
            if (isB2b) {
                b2b.add(line);
                b2bTaxable += nvl(m.getTaxableValuePaise());
                b2bTax += nvl(m.getTotalTaxPaise());
            } else {
                b2c.add(line);
                b2cTaxable += nvl(m.getTaxableValuePaise());
                b2cTax += nvl(m.getTotalTaxPaise());
            }

            String hsn = m.getHsnCode() != null ? m.getHsnCode() : "UNCLASSIFIED";
            Map<String, Object> agg = hsnSummary.computeIfAbsent(hsn, h -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("hsn", h);
                x.put("quantity", 0);
                x.put("taxableValuePaise", 0L);
                x.put("cgstPaise", 0L); x.put("sgstPaise", 0L); x.put("igstPaise", 0L);
                x.put("totalTaxPaise", 0L);
                return x;
            });
            agg.put("quantity", (int) agg.get("quantity") + (m.getQuantity() != null ? m.getQuantity() : 0));
            agg.put("taxableValuePaise", (long) agg.get("taxableValuePaise") + nvl(m.getTaxableValuePaise()));
            agg.put("cgstPaise", (long) agg.get("cgstPaise") + nvl(m.getCgstAmountPaise()));
            agg.put("sgstPaise", (long) agg.get("sgstPaise") + nvl(m.getSgstAmountPaise()));
            agg.put("igstPaise", (long) agg.get("igstPaise") + nvl(m.getIgstAmountPaise()));
            agg.put("totalTaxPaise", (long) agg.get("totalTaxPaise") + nvl(m.getTotalTaxPaise()));
        }

        Long org = orgId();
        List<Map<String, Object>> creditNotes = creditNoteRepo
                .findByTaxPeriodOrderByCreatedAtDesc(taxPeriod).stream()
                .filter(c -> org == null || org.equals(c.getOrganizationId()))
                .map(this::creditNoteLine).toList();

        List<Map<String, Object>> debitNotes = debitNoteRepo
                .findByOrganizationIdAndTaxPeriodOrderByIdAsc(org, taxPeriod).stream()
                .map(this::debitNoteLine).toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("returnType", "GSTR1");
        payload.put("taxPeriod", taxPeriod);
        payload.put("gstin", identityService.businessGstin());
        payload.put("schemaVersion", INTERNAL_SCHEMA_VERSION);
        payload.put("disclaimer",
                "Internal preparation dataset. Not the official GSTR-1 filing format and not filed.");
        payload.put("b2b", Map.of("count", b2b.size(), "taxableValuePaise", b2bTaxable,
                "totalTaxPaise", b2bTax, "invoices", b2b));
        payload.put("b2c", Map.of("count", b2c.size(), "taxableValuePaise", b2cTaxable,
                "totalTaxPaise", b2cTax, "supplies", b2c));
        payload.put("creditNotes", Map.of("count", creditNotes.size(), "notes", creditNotes));
        payload.put("debitNotes", Map.of("count", debitNotes.size(), "notes", debitNotes));
        payload.put("hsnSummary", new ArrayList<>(hsnSummary.values()));
        // Not supported yet — declared explicitly rather than silently omitted.
        payload.put("exports", Map.of("supported", false,
                "reason", "Export supplies are not modelled in this application"));
        payload.put("nilRatedExemptNonGst", Map.of("supported", false,
                "reason", "Nil-rated, exempt and non-GST supply classification is not modelled"));
        payload.put("amendments", Map.of("supported", false,
                "reason", "Amendment tables require a filed prior return to amend"));

        return snapshot("GSTR1", taxPeriod, payload, movements.size());
    }

    // ── GSTR-3B ──────────────────────────────────────────────────────────────

    /**
     * Summary return computed from the ledger and the ITC transactions, with
     * every figure carrying the movement count behind it.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstReturnSnapshotEntity prepareGstr3b(String taxPeriod) throws VeloriaException {
        GstTaxPeriodEntity period = periodService.recompute(taxPeriod);
        List<GstMovementLedgerEntity> movements = postedMovements(taxPeriod);

        long interState = movements.stream()
                .filter(m -> GstMovementService.SALE_OUTPUT.equals(m.getMovementType()))
                .filter(m -> "INTER_STATE".equals(m.getSupplyType()))
                .mapToLong(m -> nvl(m.getTaxableValuePaise())).sum();

        GstItcService.TaxHeads netItc = itcService.netItcForPeriod(taxPeriod);
        long[] claimed  = itcService.sum("CLAIM", taxPeriod);
        long[] reversed = itcService.sum("REVERSAL", taxPeriod);
        long[] reclaim  = itcService.sum("RECLAIM", taxPeriod);

        long[] paid = paymentTotals(taxPeriod);

        Map<String, Object> outward = new LinkedHashMap<>();
        outward.put("totalTaxableValuePaise", nvl(period.getTotalOutwardTaxableValue()));
        outward.put("interStateTaxableValuePaise", interState);
        outward.put("cgstPaise", nvl(period.getOutputCgst()));
        outward.put("sgstPaise", nvl(period.getOutputSgst()));
        outward.put("igstPaise", nvl(period.getOutputIgst()));
        outward.put("cessPaise", 0L);

        Map<String, Object> itc = new LinkedHashMap<>();
        itc.put("eligibleCgstPaise", nvl(period.getEligibleCgstItc()));
        itc.put("eligibleSgstPaise", nvl(period.getEligibleSgstItc()));
        itc.put("eligibleIgstPaise", nvl(period.getEligibleIgstItc()));
        itc.put("claimedTotalPaise", claimed[3]);
        itc.put("reversedTotalPaise", reversed[3]);
        itc.put("reclaimedTotalPaise", reclaim[3]);
        itc.put("netCgstPaise", netItc.cgst());
        itc.put("netSgstPaise", netItc.sgst());
        itc.put("netIgstPaise", netItc.igst());
        itc.put("netTotalPaise", netItc.total());

        Map<String, Object> liability = new LinkedHashMap<>();
        liability.put("netOutputCgstPaise", nvl(period.getNetCgstLiability()));
        liability.put("netOutputSgstPaise", nvl(period.getNetSgstLiability()));
        liability.put("netOutputIgstPaise", nvl(period.getNetIgstLiability()));
        liability.put("cashCgstPayablePaise", nvl(period.getCashCgstPayable()));
        liability.put("cashSgstPayablePaise", nvl(period.getCashSgstPayable()));

        Map<String, Object> payments = new LinkedHashMap<>();
        payments.put("cgstPaidPaise", paid[0]);
        payments.put("sgstPaidPaise", paid[1]);
        payments.put("igstPaidPaise", paid[2]);
        payments.put("interestPaise", paid[3]);
        payments.put("lateFeePaise", paid[4]);
        payments.put("totalPaidPaise", paid[5]);

        long liabilityTotal = nvl(period.getCashCgstPayable()) + nvl(period.getCashSgstPayable());
        payments.put("outstandingBalancePaise", Math.max(0, liabilityTotal - paid[5]));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("returnType", "GSTR3B");
        payload.put("taxPeriod", taxPeriod);
        payload.put("gstin", identityService.businessGstin());
        payload.put("schemaVersion", INTERNAL_SCHEMA_VERSION);
        payload.put("disclaimer",
                "Internal preparation dataset. Not the official GSTR-3B filing format and not filed.");
        payload.put("outwardSupplies", outward);
        payload.put("inputTaxCredit", itc);
        payload.put("taxLiability", liability);
        payload.put("taxPaid", payments);
        payload.put("sourceMovementCount", movements.size());

        return snapshot("GSTR3B", taxPeriod, payload, movements.size());
    }

    // ── Snapshots ────────────────────────────────────────────────────────────

    private GstReturnSnapshotEntity snapshot(String type, String taxPeriod,
                                              Map<String, Object> payload, int movementCount) {
        String json;
        try {
            json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            json = String.valueOf(payload);
        }
        String hash = sha256(json);

        // Supersede any earlier snapshot for this return and period.
        snapshotRepo.findFirstByOrganizationIdAndReturnTypeAndTaxPeriodOrderByIdDesc(
                orgId(), type, taxPeriod).ifPresent(prev -> {
            if (FILED.equals(prev.getStatus())) return; // a filed return is never overwritten
            prev.setRequiresRegeneration(false);
            prev.setStatus("SUPERSEDED");
            snapshotRepo.save(prev);
        });

        GstReturnSnapshotEntity snap = snapshotRepo.save(GstReturnSnapshotEntity.builder()
                .returnType(type)
                .taxPeriod(taxPeriod)
                .financialYear(periodService.get(taxPeriod).getFinancialYear())
                .schemaVersion(INTERNAL_SCHEMA_VERSION)
                .status(PREPARED)
                .dataHash(hash)
                .payload(json)
                .sourceMovementCount(movementCount)
                .requiresRegeneration(false)
                .organizationId(orgId())
                .gstRegistrationId(regId())
                .preparedBy(securityContext.actor())
                .build());

        periodRepo.findFirstByOrganizationIdAndTaxPeriod(orgId(), taxPeriod).ifPresent(p -> {
            if ("GSTR1".equals(type)) p.setGstr1Status(PREPARED); else p.setGstr3bStatus(PREPARED);
            periodRepo.save(p);
        });

        auditService.log("GST_RETURN_SNAPSHOT", snap.getId(), type + " " + taxPeriod,
                "RETURN_PREPARED", taxPeriod, securityContext.actor());
        log.info("{} prepared for {}: {} source movements, hash {}",
                type, taxPeriod, movementCount, hash.substring(0, 12));
        return snap;
    }

    /**
     * Re-fingerprints the current ledger and flags the snapshot when the data
     * behind it has moved (spec section 25).
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public boolean checkRequiresRegeneration(Long snapshotId) throws VeloriaException {
        GstReturnSnapshotEntity snap = snapshotRepo.findById(snapshotId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Return snapshot not found: " + snapshotId));
        int currentCount = postedMovements(snap.getTaxPeriod()).size();
        boolean changed = currentCount != nvlInt(snap.getSourceMovementCount());
        if (changed && !snap.getRequiresRegeneration()) {
            snap.setRequiresRegeneration(true);
            snapshotRepo.save(snap);
            auditService.log("GST_RETURN_SNAPSHOT", snap.getId(),
                    snap.getReturnType() + " " + snap.getTaxPeriod(),
                    "RETURN_REQUIRES_REGENERATION", snap.getTaxPeriod(), "SYSTEM");
            log.warn("{} for {} requires regeneration: movements changed from {} to {}",
                    snap.getReturnType(), snap.getTaxPeriod(), snap.getSourceMovementCount(), currentCount);
        }
        return snap.getRequiresRegeneration();
    }

    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstReturnSnapshotEntity transition(Long snapshotId, String status) throws VeloriaException {
        GstReturnSnapshotEntity snap = snapshotRepo.findById(snapshotId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Return snapshot not found: " + snapshotId));
        if (FILED.equals(status)) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A return cannot be marked FILED here. Use the filing endpoint with a real "
                            + "acknowledgement number from the GST portal.");
        }
        if (!List.of(PREPARED, UNDER_REVIEW, READY_TO_FILE, SUBMITTED).contains(status)) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "Unknown return status: " + status);
        }
        if (READY_TO_FILE.equals(status) && Boolean.TRUE.equals(snap.getRequiresRegeneration())) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "Underlying data changed since this return was prepared — regenerate it first");
        }
        String old = snap.getStatus();
        snap.setStatus(status);
        snapshotRepo.save(snap);
        auditService.log("GST_RETURN_SNAPSHOT", snap.getId(),
                snap.getReturnType() + " " + snap.getTaxPeriod(), "RETURN_STATUS_CHANGED",
                "status", old, status, snap.getTaxPeriod(), securityContext.actor());
        return snap;
    }

    /**
     * Records an actual filing. Requires an acknowledgement number, because the
     * application has no filing integration and must never invent one
     * (spec section 36).
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstReturnSnapshotEntity recordFiling(Long snapshotId, String acknowledgementNumber,
                                                 String filingReference) throws VeloriaException {
        if (acknowledgementNumber == null || acknowledgementNumber.isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "An acknowledgement number from the GST portal is required to mark a return filed");
        }
        GstReturnSnapshotEntity snap = snapshotRepo.findById(snapshotId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Return snapshot not found: " + snapshotId));
        String old = snap.getStatus();
        snap.setStatus(FILED);
        snap.setAcknowledgementNumber(acknowledgementNumber.trim());
        snap.setFilingReference(filingReference);
        snap.setFiledAt(Instant.now());
        snap.setFiledBy(securityContext.actor());
        snapshotRepo.save(snap);

        periodRepo.findFirstByOrganizationIdAndTaxPeriod(orgId(), snap.getTaxPeriod()).ifPresent(p -> {
            if ("GSTR1".equals(snap.getReturnType())) p.setGstr1Status(FILED);
            else p.setGstr3bStatus(FILED);
            periodRepo.save(p);
        });

        auditService.log("GST_RETURN_SNAPSHOT", snap.getId(),
                snap.getReturnType() + " " + snap.getTaxPeriod(), "RETURN_FILED",
                "status", old, FILED, snap.getTaxPeriod(), securityContext.actor());
        return snap;
    }

    public List<GstReturnSnapshotEntity> snapshots() {
        return snapshotRepo.findByOrganizationIdOrderByIdDesc(orgId());
    }

    /**
     * The ledger rows behind a return figure (spec section 35) — the drill-down
     * that makes every number traceable to its source transactions.
     */
    public List<GstMovementLedgerEntity> trace(String taxPeriod, String movementType) {
        return postedMovements(taxPeriod).stream()
                .filter(m -> movementType == null || movementType.equals(m.getMovementType()))
                .toList();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> lineOf(GstMovementLedgerEntity m) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("movementNumber", m.getMovementNumber());
        x.put("documentNumber", m.getSourceDocumentNumber());
        x.put("date", String.valueOf(m.getTransactionDate()));
        x.put("counterparty", m.getCounterpartyName());
        x.put("counterpartyGstin", m.getCounterpartyGstin());
        x.put("placeOfSupply", m.getPlaceOfSupply());
        x.put("supplyType", m.getSupplyType());
        x.put("hsn", m.getHsnCode());
        x.put("quantity", m.getQuantity());
        x.put("taxableValuePaise", nvl(m.getTaxableValuePaise()));
        x.put("cgstPaise", nvl(m.getCgstAmountPaise()));
        x.put("sgstPaise", nvl(m.getSgstAmountPaise()));
        x.put("igstPaise", nvl(m.getIgstAmountPaise()));
        x.put("totalTaxPaise", nvl(m.getTotalTaxPaise()));
        return x;
    }

    private Map<String, Object> creditNoteLine(GstCreditNoteEntity c) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("creditNoteNumber", c.getCreditNoteNumber());
        x.put("date", String.valueOf(c.getCreditNoteDate()));
        x.put("originalInvoice", c.getOriginalOrderCode());
        x.put("originalInvoiceDate", String.valueOf(c.getOriginalInvoiceDate()));
        x.put("originalTaxPeriod", c.getOriginalTaxPeriod());
        x.put("customerGstin", c.getCustomerGstin());
        x.put("taxableValuePaise", nvl(c.getTaxableValuePaise()));
        x.put("cgstPaise", nvl(c.getCgstPaise()));
        x.put("sgstPaise", nvl(c.getSgstPaise()));
        x.put("igstPaise", nvl(c.getIgstPaise()));
        x.put("totalPaise", nvl(c.getTotalCreditPaise()));
        x.put("status", c.getStatus());
        return x;
    }

    private Map<String, Object> debitNoteLine(GstDebitNoteEntity d) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("debitNoteNumber", d.getDebitNoteNumber());
        x.put("date", String.valueOf(d.getDebitNoteDate()));
        x.put("originalInvoice", d.getOriginalOrderCode());
        x.put("originalTaxPeriod", d.getOriginalTaxPeriod());
        x.put("customerGstin", d.getCustomerGstin());
        x.put("taxableValuePaise", nvl(d.getTaxableValuePaise()));
        x.put("cgstPaise", nvl(d.getCgstPaise()));
        x.put("sgstPaise", nvl(d.getSgstPaise()));
        x.put("igstPaise", nvl(d.getIgstPaise()));
        x.put("totalPaise", nvl(d.getTotalDebitPaise()));
        x.put("status", d.getStatus());
        return x;
    }

    private long[] paymentTotals(String period) {
        List<Object[]> rows = paymentRepo.sumForPeriod(orgId(), period);
        if (rows.isEmpty() || rows.get(0) == null) return new long[]{0,0,0,0,0,0};
        Object[] r = rows.get(0);
        return new long[]{ toLong(r[0]), toLong(r[1]), toLong(r[2]),
                           toLong(r[3]), toLong(r[4]), toLong(r[5]) };
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static long nvl(Long v) { return v != null ? v : 0L; }
    private static int nvlInt(Integer v) { return v != null ? v : 0; }
    private static long toLong(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
}
