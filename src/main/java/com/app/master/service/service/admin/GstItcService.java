package com.app.master.service.service.admin;

import com.app.master.service.core.entity.*;
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
import java.util.List;
import java.util.Set;

/**
 * Input Tax Credit lifecycle (spec sections 6 to 8, 11 and 12).
 *
 * Every claim, reversal and reclaim is a {@link GstItcTransactionEntity} with a
 * unique reference, and posts a matching row to the GST movement ledger. The
 * balances on {@code gst_input_tax} are only ever moved by this service, and
 * the database enforces the invariants independently:
 *
 *   claimed   <= eligible
 *   reversed  <= claimed
 *   reclaimed <= reversed
 *
 * Vendor GST is never automatically claimable. An invoice arrives as
 * PENDING_REVIEW and a human decides eligibility with a reason code.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstItcService {

    private final GstInputTaxRepository inputTaxRepo;
    private final GstItcTransactionRepository itcTxnRepo;
    private final GstMovementLedgerRepository movementRepo;
    private final GstIdentityService identityService;
    private final GstAuditService auditService;
    private final GstTaxPeriodService periodService;
    private final com.app.master.service.core.security.GstSecurityContext securityContext;

    // ── Vocabulary ───────────────────────────────────────────────────────────

    public static final String PENDING_REVIEW      = "PENDING_REVIEW";
    public static final String ELIGIBLE            = "ELIGIBLE";
    public static final String PARTIALLY_ELIGIBLE  = "PARTIALLY_ELIGIBLE";
    public static final String INELIGIBLE          = "INELIGIBLE";
    public static final String CLAIMED             = "CLAIMED";
    public static final String REVERSED            = "REVERSED";
    public static final String RECLAIMED           = "RECLAIMED";

    public static final Set<String> ELIGIBILITY_STATES =
            Set.of(PENDING_REVIEW, ELIGIBLE, PARTIALLY_ELIGIBLE, INELIGIBLE);

    /**
     * Reason codes (spec section 8). Configurable rather than hard-coded legal
     * conclusions — the list is data for the UI, and the statutory meaning of
     * each is for the business's accountant to confirm.
     */
    public static final List<String> REASON_CODES = List.of(
            "VALID_DOCUMENT", "MISSING_DOCUMENT", "INVALID_GSTIN", "NOT_REFLECTED_IN_2B",
            "BLOCKED_CREDIT", "PERSONAL_USE", "NON_BUSINESS_USE", "PARTIAL_BUSINESS_USE",
            "RETURNED_GOODS", "CREDIT_NOTE_RECEIVED", "OTHER");

    public record TaxHeads(long cgst, long sgst, long igst) {
        public long total() { return cgst + sgst + igst; }
        public boolean isZero() { return total() == 0; }
        static TaxHeads of(long c, long s, long i) { return new TaxHeads(c, s, i); }
    }

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

    // ── Eligibility ──────────────────────────────────────────────────────────

    /**
     * Records the eligibility decision for a purchase invoice.
     *
     * PARTIALLY_ELIGIBLE requires the caller to state the eligible split, so a
     * partial decision cannot silently become a full one.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstInputTaxEntity setEligibility(Long inputTaxId, String eligibility, String reasonCode,
                                            String notes, TaxHeads eligibleSplit) throws VeloriaException {
        GstInputTaxEntity it = load(inputTaxId);
        periodService.assertOpen(it.getTaxPeriod());

        if (!ELIGIBILITY_STATES.contains(eligibility)) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Eligibility must be one of " + ELIGIBILITY_STATES);
        }
        if (reasonCode != null && !REASON_CODES.contains(reasonCode)) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Unknown ITC reason code '" + reasonCode + "'. Valid codes: " + REASON_CODES);
        }
        if (nvl(it.getClaimedCgst()) + nvl(it.getClaimedSgst()) + nvl(it.getClaimedIgst()) > 0) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "ITC has already been claimed on invoice " + it.getInvoiceNumber()
                            + "; reverse it before changing eligibility");
        }

        TaxHeads full = TaxHeads.of(nvl(it.getCgstAmount()), nvl(it.getSgstAmount()), nvl(it.getIgstAmount()));
        TaxHeads eligible = switch (eligibility) {
            case ELIGIBLE -> full;
            case INELIGIBLE, PENDING_REVIEW -> TaxHeads.of(0, 0, 0);
            case PARTIALLY_ELIGIBLE -> requirePartialSplit(eligibleSplit, full, it);
            default -> TaxHeads.of(0, 0, 0);
        };

        String old = it.getItcEligibility();
        it.setItcEligibility(eligibility);
        it.setItcStatus(eligibility);
        it.setEligibilityReason(reasonCode);
        it.setEligibilityNotes(notes);
        it.setEligibleCgst(eligible.cgst());
        it.setEligibleSgst(eligible.sgst());
        it.setEligibleIgst(eligible.igst());
        it.setIneligibleCgst(full.cgst() - eligible.cgst());
        it.setIneligibleSgst(full.sgst() - eligible.sgst());
        it.setIneligibleIgst(full.igst() - eligible.igst());
        inputTaxRepo.save(it);

        auditService.log("GST_INPUT_TAX", it.getId(), it.getInvoiceNumber(),
                "ITC_ELIGIBILITY_SET", "itcEligibility", old, eligibility,
                it.getTaxPeriod(), securityContext.actor());

        log.info("ITC eligibility for invoice {} set to {} ({}), eligible {} paise",
                it.getInvoiceNumber(), eligibility, reasonCode, eligible.total());
        return it;
    }

    private TaxHeads requirePartialSplit(TaxHeads split, TaxHeads full, GstInputTaxEntity it)
            throws VeloriaException {
        if (split == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "PARTIALLY_ELIGIBLE requires the eligible CGST/SGST/IGST split");
        }
        if (split.cgst() < 0 || split.sgst() < 0 || split.igst() < 0) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "Eligible amounts cannot be negative");
        }
        if (split.cgst() > full.cgst() || split.sgst() > full.sgst() || split.igst() > full.igst()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Eligible ITC cannot exceed the tax charged on invoice " + it.getInvoiceNumber());
        }
        return split;
    }

    // ── Claim ────────────────────────────────────────────────────────────────

    /**
     * Claims eligible ITC. Idempotent on the transaction reference, so a repeat
     * call does not claim twice.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstItcTransactionEntity claim(Long inputTaxId, String notes) throws VeloriaException {
        GstInputTaxEntity it = load(inputTaxId);
        periodService.assertOpen(it.getTaxPeriod());

        if (!ELIGIBLE.equals(it.getItcEligibility()) && !PARTIALLY_ELIGIBLE.equals(it.getItcEligibility())) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "ITC on invoice " + it.getInvoiceNumber() + " is " + it.getItcEligibility()
                            + "; only ELIGIBLE or PARTIALLY_ELIGIBLE credit can be claimed");
        }

        // Idempotency is checked before the claimable-amount guard: a repeat
        // call must return the existing claim, not fail with "nothing left".
        String ref = "GST-ITC-CLAIM-" + it.getId();
        if (itcTxnRepo.existsByTransactionReference(ref)) {
            return itcTxnRepo.findByTransactionReference(ref).orElseThrow();
        }

        TaxHeads claimable = TaxHeads.of(
                nvl(it.getEligibleCgst()) - nvl(it.getClaimedCgst()),
                nvl(it.getEligibleSgst()) - nvl(it.getClaimedSgst()),
                nvl(it.getEligibleIgst()) - nvl(it.getClaimedIgst()));
        if (claimable.isZero()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Nothing left to claim on invoice " + it.getInvoiceNumber());
        }

        GstItcTransactionEntity txn = post("CLAIM", it, claimable, "VALID_DOCUMENT", notes, ref, null,
                GstMovementService.ITC_CLAIM, GstMovementService.DIR_ITC, +1);

        it.setClaimedCgst(nvl(it.getClaimedCgst()) + claimable.cgst());
        it.setClaimedSgst(nvl(it.getClaimedSgst()) + claimable.sgst());
        it.setClaimedIgst(nvl(it.getClaimedIgst()) + claimable.igst());
        it.setItcStatus(CLAIMED);
        it.setItcClaimedAt(Instant.now());
        it.setItcClaimedPeriod(currentPeriod());
        inputTaxRepo.save(it);

        auditService.log("GST_INPUT_TAX", it.getId(), it.getInvoiceNumber(),
                "ITC_CLAIMED", null, null, String.valueOf(claimable.total()),
                it.getTaxPeriod(), securityContext.actor());
        return txn;
    }

    // ── Reversal ─────────────────────────────────────────────────────────────

    /**
     * Reverses claimed ITC, fully or partially. Cannot reverse more than has
     * been claimed — checked here and again by a database constraint.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstItcTransactionEntity reverse(Long inputTaxId, TaxHeads amount, String reasonCode,
                                            String notes) throws VeloriaException {
        GstInputTaxEntity it = load(inputTaxId);
        periodService.assertOpen(currentPeriod());
        requireReason(reasonCode);

        TaxHeads outstanding = TaxHeads.of(
                nvl(it.getClaimedCgst()) - nvl(it.getReversedCgst()),
                nvl(it.getClaimedSgst()) - nvl(it.getReversedSgst()),
                nvl(it.getClaimedIgst()) - nvl(it.getReversedIgst()));

        TaxHeads amt = amount != null ? amount : outstanding;
        if (amt.isZero()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Nothing to reverse on invoice " + it.getInvoiceNumber());
        }
        if (amt.cgst() > outstanding.cgst() || amt.sgst() > outstanding.sgst() || amt.igst() > outstanding.igst()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Cannot reverse more ITC than was claimed on invoice " + it.getInvoiceNumber()
                            + " (outstanding " + outstanding.total() + " paise, requested " + amt.total() + ")");
        }

        String ref = "GST-ITC-REV-" + it.getId() + "-" + (itcTxnRepo.findByInputTaxIdOrderByIdAsc(it.getId()).size() + 1);
        GstItcTransactionEntity txn = post("REVERSAL", it, amt, reasonCode, notes, ref, null,
                GstMovementService.ITC_REVERSAL, GstMovementService.DIR_ITC, -1);

        it.setReversedCgst(nvl(it.getReversedCgst()) + amt.cgst());
        it.setReversedSgst(nvl(it.getReversedSgst()) + amt.sgst());
        it.setReversedIgst(nvl(it.getReversedIgst()) + amt.igst());
        it.setReversalAmount(nvl(it.getReversalAmount()) + amt.total());
        it.setReversalReason(reasonCode);
        it.setItcStatus(REVERSED);
        inputTaxRepo.save(it);

        auditService.log("GST_INPUT_TAX", it.getId(), it.getInvoiceNumber(),
                "ITC_REVERSED", "reversedAmount", null, String.valueOf(amt.total()),
                currentPeriod(), securityContext.actor());
        log.info("ITC reversed on invoice {}: {} paise, reason {}", it.getInvoiceNumber(), amt.total(), reasonCode);
        return txn;
    }

    // ── Reclaim ──────────────────────────────────────────────────────────────

    /**
     * Reclaims previously reversed ITC once the condition is satisfied.
     * Cannot reclaim more than was reversed.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstItcTransactionEntity reclaim(Long inputTaxId, TaxHeads amount, String reasonCode,
                                            String notes) throws VeloriaException {
        GstInputTaxEntity it = load(inputTaxId);
        periodService.assertOpen(currentPeriod());
        requireReason(reasonCode);

        TaxHeads reclaimable = TaxHeads.of(
                nvl(it.getReversedCgst()) - nvl(it.getReclaimedCgst()),
                nvl(it.getReversedSgst()) - nvl(it.getReclaimedSgst()),
                nvl(it.getReversedIgst()) - nvl(it.getReclaimedIgst()));

        TaxHeads amt = amount != null ? amount : reclaimable;
        if (amt.isZero()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Nothing to reclaim on invoice " + it.getInvoiceNumber());
        }
        if (amt.cgst() > reclaimable.cgst() || amt.sgst() > reclaimable.sgst() || amt.igst() > reclaimable.igst()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Cannot reclaim more ITC than was reversed on invoice " + it.getInvoiceNumber()
                            + " (reclaimable " + reclaimable.total() + " paise, requested " + amt.total() + ")");
        }

        String ref = "GST-ITC-RCL-" + it.getId() + "-" + (itcTxnRepo.findByInputTaxIdOrderByIdAsc(it.getId()).size() + 1);
        GstItcTransactionEntity txn = post("RECLAIM", it, amt, reasonCode, notes, ref, null,
                GstMovementService.ITC_RECLAIM, GstMovementService.DIR_ITC, +1);

        it.setReclaimedCgst(nvl(it.getReclaimedCgst()) + amt.cgst());
        it.setReclaimedSgst(nvl(it.getReclaimedSgst()) + amt.sgst());
        it.setReclaimedIgst(nvl(it.getReclaimedIgst()) + amt.igst());
        it.setReclaimedAmount(nvl(it.getReclaimedAmount()) + amt.total());
        it.setItcStatus(RECLAIMED);
        inputTaxRepo.save(it);

        auditService.log("GST_INPUT_TAX", it.getId(), it.getInvoiceNumber(),
                "ITC_RECLAIMED", "reclaimedAmount", null, String.valueOf(amt.total()),
                currentPeriod(), securityContext.actor());
        log.info("ITC reclaimed on invoice {}: {} paise, reason {}", it.getInvoiceNumber(), amt.total(), reasonCode);
        return txn;
    }

    // ── Shared posting ───────────────────────────────────────────────────────

    private GstItcTransactionEntity post(String type, GstInputTaxEntity it, TaxHeads amt,
                                          String reasonCode, String notes, String ref,
                                          Long reversalOfId, String movementType,
                                          String direction, int sign) {
        String period = currentPeriod();
        String fy = financialYear(LocalDate.now());

        GstMovementLedgerEntity movement = movementRepo.save(GstMovementLedgerEntity.builder()
                .movementNumber(ref)
                .movementType(movementType)
                .direction(direction)
                .sourceType("ITC_TRANSACTION")
                .sourceDocumentNumber(it.getInvoiceNumber())
                .transactionDate(LocalDate.now())
                .taxPeriod(period)
                .financialYear(fy)
                .businessGstin(identityService.businessGstin())
                .counterpartyGstin(it.getVendorGstin())
                .counterpartyName(it.getVendorName())
                .counterpartyType("VENDOR")
                .cgstAmountPaise(sign * amt.cgst())
                .sgstAmountPaise(sign * amt.sgst())
                .igstAmountPaise(sign * amt.igst())
                .totalTaxPaise(sign * amt.total())
                .itcStatus(type)
                .status("POSTED")
                .reason(type + (reasonCode != null ? " — " + reasonCode : ""))
                .createdBy(securityContext.actor())
                .organizationId(orgId())
                .gstRegistrationId(regId())
                .build());

        GstItcTransactionEntity txn = itcTxnRepo.save(GstItcTransactionEntity.builder()
                .transactionReference(ref)
                .inputTaxId(it.getId())
                .transactionType(type)
                .reasonCode(reasonCode)
                .reasonNotes(notes)
                .cgstPaise(amt.cgst())
                .sgstPaise(amt.sgst())
                .igstPaise(amt.igst())
                .totalPaise(amt.total())
                .taxPeriod(period)
                .financialYear(fy)
                .movementId(movement.getId())
                .reversalOfId(reversalOfId)
                .organizationId(orgId())
                .gstRegistrationId(regId())
                .createdBy(securityContext.actor())
                .build());

        // Link the ledger row back so drill-down works in both directions.
        movement.setSourceId(txn.getId());
        movementRepo.save(movement);
        return txn;
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    public List<GstItcTransactionEntity> transactionsFor(Long inputTaxId) {
        return itcTxnRepo.findByInputTaxIdOrderByIdAsc(inputTaxId);
    }

    /** Net ITC available for offset in a period: claims minus reversals plus reclaims. */
    public TaxHeads netItcForPeriod(String period) {
        long[] claim   = sum("CLAIM", period);
        long[] reverse = sum("REVERSAL", period);
        long[] reclaim = sum("RECLAIM", period);
        return TaxHeads.of(
                claim[0] - reverse[0] + reclaim[0],
                claim[1] - reverse[1] + reclaim[1],
                claim[2] - reverse[2] + reclaim[2]);
    }

    public long[] sum(String type, String period) {
        List<Object[]> rows = itcTxnRepo.sumByType(orgId(), type, period);
        if (rows.isEmpty() || rows.get(0) == null) return new long[]{0, 0, 0, 0};
        Object[] r = rows.get(0);
        return new long[]{ toLong(r[0]), toLong(r[1]), toLong(r[2]), toLong(r[3]) };
    }

    private GstInputTaxEntity load(Long id) throws VeloriaException {
        GstInputTaxEntity it = inputTaxRepo.findById(id)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Purchase invoice not found: " + id));
        Long callerOrg = orgId();
        if (it.getOrganizationId() != null && callerOrg != null
                && !it.getOrganizationId().equals(callerOrg)) {
            // Report as not found rather than confirming the row exists elsewhere.
            throw new VeloriaException(ResponseCode.NOT_FOUND, "Purchase invoice not found: " + id);
        }
        return it;
    }

    private void requireReason(String reasonCode) throws VeloriaException {
        if (reasonCode == null || !REASON_CODES.contains(reasonCode)) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A valid reason code is required. Valid codes: " + REASON_CODES);
        }
    }

    private String currentPeriod() { return YearMonth.now().toString(); }

    private String financialYear(LocalDate d) {
        int yr = d.getYear();
        return d.getMonthValue() >= 4
                ? yr + "-" + String.format("%02d", (yr + 1) % 100)
                : (yr - 1) + "-" + String.format("%02d", yr % 100);
    }

    private static long nvl(Long v) { return v != null ? v : 0L; }
    private static long toLong(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
}
