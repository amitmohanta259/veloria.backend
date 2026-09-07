package com.app.master.service.service.admin;

import com.app.master.service.core.entity.GstRegistrationEntity;
import com.app.master.service.core.entity.GstTaxPeriodEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tax period lifecycle, totals and locking (spec sections 20, 21, 29 and 51).
 *
 * The gst_tax_period table existed but had no entity, no service and no rows —
 * totals were never computed and nothing prevented writing into a filed period.
 *
 * Locking is enforced twice: this service refuses the operation, and a database
 * trigger rejects any ledger insert or update into a LOCKED or FILED period, so
 * an application bug cannot quietly post into a closed period.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstTaxPeriodService {

    public static final String OPEN             = "OPEN";
    public static final String UNDER_REVIEW     = "UNDER_REVIEW";
    public static final String READY_FOR_FILING = "READY_FOR_FILING";
    public static final String FILED            = "FILED";
    public static final String LOCKED           = "LOCKED";

    private final GstTaxPeriodRepository periodRepo;
    private final GstMovementLedgerRepository movementRepo;
    private final GstInputTaxRepository inputTaxRepo;
    private final GstCreditNoteRepository creditNoteRepo;
    private final GstIdentityService identityService;
    private final GstAuditService auditService;
    private final com.app.master.service.core.security.GstSecurityContext securityContext;

    private Long orgId() {
        return securityContext.current()
                .map(com.app.master.service.core.security.GstPrincipal::organizationId)
                .filter(java.util.Objects::nonNull)
                .orElseGet(identityService::defaultOrganizationId);
    }

    private Long regId() {
        return securityContext.current()
                .map(com.app.master.service.core.security.GstPrincipal::gstRegistrationId)
                .filter(java.util.Objects::nonNull)
                .orElseGet(() -> identityService.primaryRegistration(orgId())
                        .map(GstRegistrationEntity::getId).orElse(null));
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** The period record, created on first use. */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstTaxPeriodEntity ensurePeriod(String taxPeriod) {
        Long org = orgId();
        Long reg = regId();
        return periodRepo.findByOrganizationIdAndGstRegistrationIdAndTaxPeriod(org, reg, taxPeriod)
                .orElseGet(() -> {
                    YearMonth ym = YearMonth.parse(taxPeriod);
                    return periodRepo.save(GstTaxPeriodEntity.builder()
                            .taxPeriod(taxPeriod)
                            .financialYear(financialYear(ym.atDay(1)))
                            .periodStartDate(ym.atDay(1))
                            .periodEndDate(ym.atEndOfMonth())
                            .status(OPEN)
                            .organizationId(org)
                            .gstRegistrationId(reg)
                            .build());
                });
    }

    /** Throws when the period is closed to new accounting entries. */
    public void assertOpen(String taxPeriod) throws VeloriaException {
        if (taxPeriod == null) return;
        var existing = periodRepo.findFirstByOrganizationIdAndTaxPeriod(orgId(), taxPeriod);
        if (existing.isPresent() && existing.get().isLocked()) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "GST period " + taxPeriod + " is " + existing.get().getStatus()
                            + ". Post a credit note, debit note or adjustment in an open period instead.");
        }
    }

    public boolean isLocked(String taxPeriod) {
        return periodRepo.findFirstByOrganizationIdAndTaxPeriod(orgId(), taxPeriod)
                .map(GstTaxPeriodEntity::isLocked).orElse(false);
    }

    // ── Computation (spec section 29) ────────────────────────────────────────

    /**
     * Recomputes every total for a period from the ledger, never from orders.
     * Safe to run repeatedly; it derives rather than accumulates.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstTaxPeriodEntity recompute(String taxPeriod) {
        GstTaxPeriodEntity p = ensurePeriod(taxPeriod);
        Long org = orgId();

        long[] out = agg(movementRepo.sumByDirectionAndPeriod(org, "OUT", taxPeriod));
        long[] in  = agg(movementRepo.sumByDirectionAndPeriod(org, "IN", taxPeriod));
        long[] adj = agg(movementRepo.sumByMovementTypeAndPeriod(
                org, GstMovementService.CUSTOMER_RETURN_OUTPUT_ADJUSTMENT, taxPeriod));

        p.setOutputCgst(out[0]);
        p.setOutputSgst(out[1]);
        p.setOutputIgst(out[2]);
        p.setTotalOutputTax(out[3]);
        p.setTotalOutwardTaxableValue(out[4]);

        p.setInputCgst(in[0]);
        p.setInputSgst(in[1]);
        p.setInputIgst(in[2]);
        p.setTotalInputTax(in[3]);
        p.setTotalInwardTaxableValue(in[4]);

        // Credit notes are stored negative; flip for the reversal columns.
        p.setReversedCgst(-adj[0]);
        p.setReversedSgst(-adj[1]);
        p.setReversedIgst(-adj[2]);

        // Only ELIGIBLE credit flows into liability — never all vendor GST.
        long eligCgst = 0, eligSgst = 0, eligIgst = 0;
        for (var it : inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(taxPeriod)) {
            if (org != null && it.getOrganizationId() != null && !org.equals(it.getOrganizationId())) continue;
            eligCgst += nvl(it.getEligibleCgst());
            eligSgst += nvl(it.getEligibleSgst());
            eligIgst += nvl(it.getEligibleIgst());
        }
        p.setEligibleCgstItc(eligCgst);
        p.setEligibleSgstItc(eligSgst);
        p.setEligibleIgstItc(eligIgst);

        // Net output after credit-note adjustments, per tax head.
        long netOutCgst = Math.max(0, out[0] + adj[0]);
        long netOutSgst = Math.max(0, out[1] + adj[1]);
        long netOutIgst = Math.max(0, out[2] + adj[2]);
        p.setNetCgstLiability(netOutCgst);
        p.setNetSgstLiability(netOutSgst);
        p.setNetIgstLiability(netOutIgst);

        // Statutory utilisation order: IGST credit first against IGST, then CGST/SGST.
        long igstCredit = eligIgst;
        long igstUsed   = Math.min(netOutIgst, igstCredit);
        long igstLeft   = igstCredit - igstUsed;

        long cgstUsed = Math.min(netOutCgst, eligCgst + igstLeft);
        long igstToCgst = Math.max(0, cgstUsed - eligCgst);
        igstLeft -= igstToCgst;

        long sgstUsed = Math.min(netOutSgst, eligSgst + igstLeft);

        p.setCashCgstPayable(Math.max(0, netOutCgst - cgstUsed));
        p.setCashSgstPayable(Math.max(0, netOutSgst - sgstUsed));

        periodRepo.save(p);
        log.info("Tax period {} recomputed: output {} paise, input {} paise, eligible ITC {} paise",
                taxPeriod, out[3], in[3], eligCgst + eligSgst + eligIgst);
        return p;
    }

    // ── Validation before closing (spec sections 51 and 52) ──────────────────

    public record Finding(String severity, String code, String reference, String detail) {}

    /**
     * Pre-filing validation. ERROR blocks READY_FOR_FILING; WARNING and INFO
     * do not.
     */
    public List<Finding> validate(String taxPeriod) {
        List<Finding> findings = new ArrayList<>();
        Long org = orgId();

        var movements = movementRepo.findByTaxPeriodOrderByCreatedAtDesc(taxPeriod).stream()
                .filter(m -> org == null || org.equals(m.getOrganizationId()))
                .filter(m -> !"SUPERSEDED".equals(m.getStatus()) && !"CANCELLED".equals(m.getStatus()))
                .toList();

        if (movements.isEmpty()) {
            findings.add(new Finding("INFO", "NO_TRANSACTIONS", taxPeriod,
                    "No GST movements recorded in this period"));
        }

        for (var m : movements) {
            long c = nvl(m.getCgstAmountPaise()), s = nvl(m.getSgstAmountPaise()), i = nvl(m.getIgstAmountPaise());

            if (c != 0 && i != 0) {
                findings.add(new Finding("ERROR", "CGST_IGST_CONFLICT", m.getMovementNumber(),
                        "Both CGST and IGST are non-zero on the same supply"));
            }
            if (s != 0 && i != 0) {
                findings.add(new Finding("ERROR", "SGST_IGST_CONFLICT", m.getMovementNumber(),
                        "Both SGST and IGST are non-zero on the same supply"));
            }
            if (c + s + i != nvl(m.getTotalTaxPaise())) {
                findings.add(new Finding("ERROR", "TAX_SUM_MISMATCH", m.getMovementNumber(),
                        "CGST + SGST + IGST does not equal the recorded total tax"));
            }
            // Place of supply and HSN are outward-supply attributes reported in
            // GSTR-1. An inward movement's place of supply is our own location,
            // and an ITC claim or reversal is a credit-ledger entry with no
            // supply behind it — neither carries these fields, so checking them
            // there produces false errors that block an otherwise filable period.
            boolean outward = GstMovementService.DIR_OUT.equals(m.getDirection())
                    || GstMovementService.DIR_ADJUSTMENT.equals(m.getDirection());
            if (outward) {
                if (m.getPlaceOfSupply() == null || m.getPlaceOfSupply().isBlank()) {
                    findings.add(new Finding("ERROR", "MISSING_PLACE_OF_SUPPLY", m.getMovementNumber(),
                            "Place of supply is not set, so the tax head cannot be verified"));
                }
                if (m.getHsnCode() == null || m.getHsnCode().isBlank()) {
                    findings.add(new Finding("WARNING", "MISSING_HSN", m.getMovementNumber(),
                            "No HSN recorded — the HSN summary will be incomplete"));
                }
            }
            if (m.getTaxPeriod() == null) {
                findings.add(new Finding("ERROR", "MISSING_TAX_PERIOD", m.getMovementNumber(),
                        "Movement is not assigned to a tax period"));
            }
        }

        for (var it : inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(taxPeriod)) {
            if (org != null && it.getOrganizationId() != null && !org.equals(it.getOrganizationId())) continue;
            if (it.getVendorGstin() == null || it.getVendorGstin().isBlank()) {
                findings.add(new Finding("ERROR", "MISSING_VENDOR_GSTIN", it.getInvoiceNumber(),
                        "ITC cannot be claimed without the supplier's GSTIN"));
            } else if (!identityService.isValidGstin(it.getVendorGstin())) {
                findings.add(new Finding("ERROR", "INVALID_GSTIN", it.getInvoiceNumber(),
                        "Supplier GSTIN is not a valid format"));
            }
            if (GstItcService.PENDING_REVIEW.equals(it.getItcEligibility())) {
                findings.add(new Finding("WARNING", "ITC_PENDING_REVIEW", it.getInvoiceNumber(),
                        "ITC eligibility has not been decided"));
            }
            if (!"MATCHED".equals(it.getGstr2bMatchStatus())
                    && nvl(it.getEligibleCgst()) + nvl(it.getEligibleSgst()) + nvl(it.getEligibleIgst()) > 0) {
                findings.add(new Finding("WARNING", "ITC_WITHOUT_2B_MATCH", it.getInvoiceNumber(),
                        "Credit treated as eligible but the invoice is not matched in GSTR-2B"));
            }
        }

        for (var cn : creditNoteRepo.findByTaxPeriodOrderByCreatedAtDesc(taxPeriod)) {
            if (org != null && cn.getOrganizationId() != null && !org.equals(cn.getOrganizationId())) continue;
            if (cn.getOriginalOrderCode() == null || cn.getOriginalOrderCode().isBlank()) {
                findings.add(new Finding("ERROR", "CREDIT_NOTE_WITHOUT_INVOICE", cn.getCreditNoteNumber(),
                        "Credit note does not reference an original invoice"));
            }
            if ("PENDING_REVIEW".equals(cn.getStatus())) {
                findings.add(new Finding("WARNING", "CREDIT_NOTE_UNAPPROVED", cn.getCreditNoteNumber(),
                        "Credit note has not been approved or issued"));
            }
        }

        return findings;
    }

    public Map<String, Object> validationReport(String taxPeriod) {
        List<Finding> all = validate(taxPeriod);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taxPeriod", taxPeriod);
        out.put("errors", all.stream().filter(f -> "ERROR".equals(f.severity())).toList());
        out.put("warnings", all.stream().filter(f -> "WARNING".equals(f.severity())).toList());
        out.put("info", all.stream().filter(f -> "INFO".equals(f.severity())).toList());
        out.put("errorCount", all.stream().filter(f -> "ERROR".equals(f.severity())).count());
        out.put("warningCount", all.stream().filter(f -> "WARNING".equals(f.severity())).count());
        out.put("readyForFiling", all.stream().noneMatch(f -> "ERROR".equals(f.severity())));
        return out;
    }

    // ── Transitions ──────────────────────────────────────────────────────────

    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstTaxPeriodEntity markReadyForFiling(String taxPeriod) throws VeloriaException {
        List<Finding> findings = validate(taxPeriod);
        List<Finding> errors = findings.stream().filter(f -> "ERROR".equals(f.severity())).toList();
        if (!errors.isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Period " + taxPeriod + " has " + errors.size()
                            + " blocking validation error(s); resolve them before marking it ready for filing");
        }
        GstTaxPeriodEntity p = recompute(taxPeriod);
        String old = p.getStatus();
        p.setStatus(READY_FOR_FILING);
        periodRepo.save(p);
        auditService.log("GST_TAX_PERIOD", p.getId(), taxPeriod, "PERIOD_READY_FOR_FILING",
                "status", old, READY_FOR_FILING, taxPeriod, securityContext.actor());
        return p;
    }

    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstTaxPeriodEntity lock(String taxPeriod) throws VeloriaException {
        GstTaxPeriodEntity p = recompute(taxPeriod);
        if (p.isLocked()) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "Period " + taxPeriod + " is already " + p.getStatus());
        }
        String old = p.getStatus();
        p.setStatus(LOCKED);
        p.setLockedAt(Instant.now());
        p.setLockedBy(securityContext.actor());
        periodRepo.save(p);
        auditService.log("GST_TAX_PERIOD", p.getId(), taxPeriod, "PERIOD_LOCKED",
                "status", old, LOCKED, taxPeriod, securityContext.actor());
        log.info("GST period {} locked by {}", taxPeriod, securityContext.actor());
        return p;
    }

    /** Unlocking is an admin-only, reason-required, fully audited exception. */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstTaxPeriodEntity unlock(String taxPeriod, String reason) throws VeloriaException {
        if (reason == null || reason.isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A reason is required to unlock a GST period");
        }
        GstTaxPeriodEntity p = periodRepo.findFirstByOrganizationIdAndTaxPeriod(orgId(), taxPeriod)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Tax period not found: " + taxPeriod));
        if (!p.isLocked()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Period " + taxPeriod + " is " + p.getStatus() + " and is not locked");
        }
        String old = p.getStatus();
        p.setStatus(UNDER_REVIEW);
        p.setUnlockedAt(Instant.now());
        p.setUnlockedBy(securityContext.actor());
        p.setUnlockReason(reason);
        periodRepo.save(p);
        auditService.log("GST_TAX_PERIOD", p.getId(), taxPeriod, "PERIOD_UNLOCKED",
                "status", old, UNDER_REVIEW, taxPeriod, securityContext.actor());
        log.warn("GST period {} unlocked by {} — reason: {}", taxPeriod, securityContext.actor(), reason);
        return p;
    }

    public List<GstTaxPeriodEntity> list() {
        return periodRepo.findByOrganizationIdOrderByTaxPeriodDesc(orgId());
    }

    public GstTaxPeriodEntity get(String taxPeriod) {
        return periodRepo.findFirstByOrganizationIdAndTaxPeriod(orgId(), taxPeriod)
                .orElseGet(() -> recompute(taxPeriod));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private long[] agg(List<Object[]> rows) {
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).length < 5) return new long[]{0,0,0,0,0};
        Object[] r = rows.get(0);
        return new long[]{ toLong(r[0]), toLong(r[1]), toLong(r[2]), toLong(r[3]), toLong(r[4]) };
    }

    private String financialYear(LocalDate d) {
        int yr = d.getYear();
        return d.getMonthValue() >= 4
                ? yr + "-" + String.format("%02d", (yr + 1) % 100)
                : (yr - 1) + "-" + String.format("%02d", yr % 100);
    }

    private static long nvl(Long v) { return v != null ? v : 0L; }
    private static long toLong(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
}
