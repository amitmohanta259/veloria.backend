package com.app.master.service.service.admin;

import com.app.master.service.core.entity.*;
import com.app.master.service.repository.admin.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Unified GST Movement Ledger.
 *
 * Every GST-generating event (sale, purchase, return, credit note) writes one or more
 * rows to gst_movement_ledger. This is the single source of truth for the tracker UI
 * and all GST reporting. Existing gst_output_tax and gst_input_tax tables are kept
 * intact; movements are written in addition to them.
 *
 * All operations are idempotent: re-running backfill or re-calling record methods
 * on the same source document is a no-op.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstMovementService {

    private final GstMovementLedgerRepository movementRepo;
    private final GstCreditNoteRepository creditNoteRepo;
    private final GstOutputTaxRepository outputTaxRepo;
    private final GstInputTaxRepository inputTaxRepo;
    private final BusinessDetailsRepository businessDetailsRepo;
    private final CustomerOrderRepository customerOrderRepo;
    private final CustomerOrderItemRepository orderItemRepo;
    private final GstIdentityService identityService;
    private final com.app.master.service.core.security.GstSecurityContext securityContext;

    /** Organization owning the request, falling back to the sole tenant. */
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
                        .map(com.app.master.service.core.entity.GstRegistrationEntity::getId).orElse(null));
    }

    // ── Movement type constants ──────────────────────────────────────────────

    public static final String SALE_OUTPUT                        = "SALE_OUTPUT";
    public static final String PURCHASE_INPUT                     = "PURCHASE_INPUT";
    public static final String CUSTOMER_RETURN_OUTPUT_ADJUSTMENT  = "CUSTOMER_RETURN_OUTPUT_ADJUSTMENT";
    public static final String VENDOR_RETURN_INPUT_ADJUSTMENT     = "VENDOR_RETURN_INPUT_ADJUSTMENT";
    public static final String SALES_CREDIT_NOTE                  = "SALES_CREDIT_NOTE";
    public static final String ITC_CLAIM                          = "ITC_CLAIM";
    public static final String ITC_REVERSAL                       = "ITC_REVERSAL";
    public static final String ITC_RECLAIM                        = "ITC_RECLAIM";
    public static final String TAX_PAYMENT                        = "TAX_PAYMENT";
    public static final String OTHER_ADJUSTMENT                   = "OTHER_ADJUSTMENT";

    public static final String DIR_IN         = "IN";
    public static final String DIR_OUT        = "OUT";
    public static final String DIR_ADJUSTMENT = "ADJUSTMENT";
    /**
     * ITC claim, reversal and reclaim. Deliberately NOT DIR_IN: a claim is the
     * utilisation of tax a vendor already charged, not additional input tax, and
     * counting it as IN double-counts the "GST charged by vendors" total.
     */
    public static final String DIR_ITC        = "ITC";

    // ── Number generation ────────────────────────────────────────────────────

    private String newMovementNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "GST-MOV-" + date + "-" + suffix;
    }

    private String newCreditNoteNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "CN-" + date + "-" + suffix;
    }

    // ── Tax-period helpers ───────────────────────────────────────────────────

    private String taxPeriod(LocalDate date) {
        return YearMonth.from(date).toString(); // e.g. "2026-08"
    }

    private String financialYear(LocalDate date) {
        int yr = date.getYear();
        int month = date.getMonthValue();
        if (month >= 4) return yr + "-" + String.format("%02d", (yr + 1) % 100);
        return (yr - 1) + "-" + String.format("%02d", yr % 100);
    }

    private String businessGstin() {
        return businessDetailsRepo.findFirstByArchiveFalseOrderByIdAsc()
                .map(BusinessDetailsEntity::getGstNumber).orElse(null);
    }

    // ── Core: record a sale movement ─────────────────────────────────────────

    /**
     * Called immediately after a customer order is saved.
     * Creates one SALE_OUTPUT movement for the entire order.
     * Idempotent: skips if a movement already exists for this order id.
     */
    @Transactional
    public void recordSaleMovement(CustomerOrderEntity order,
                                   List<CustomerOrderItemEntity> items) {
        LocalDate txDate = order.getOrderPlacedAt() != null
                ? order.getOrderPlacedAt().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate()
                : LocalDate.now();

        String period = taxPeriod(txDate);
        String fy = financialYear(txDate);
        String supplyType = nvl(order.getIgstAmount()) > 0 ? "INTER_STATE" : "INTRA_STATE";

        // One movement per line. An order spanning two HSN codes now produces
        // two ledger rows, which is what GSTR-1's HSN summary and the tracker's
        // drill-down both need.
        List<GstMovementLedgerEntity> toSave = new ArrayList<>();
        for (CustomerOrderItemEntity item : items) {
            String sourceType = "CUSTOMER_ORDER_ITEM";
            if (item.getId() == null) continue;
            if (movementRepo.existsBySourceTypeAndSourceId(sourceType, item.getId())) continue;

            long cgst = nvl(item.getCgstAmount());
            long sgst = nvl(item.getSgstAmount());
            long igst = nvl(item.getIgstAmount());
            long total = cgst + sgst + igst;
            long taxable = nvl(item.getTaxableValuePaise()) > 0
                    ? nvl(item.getTaxableValuePaise())
                    : nvl(item.getUnitPricePaise()) * (item.getQuantity() != null ? item.getQuantity() : 1);

            if (total == 0 && taxable == 0) continue;

            toSave.add(GstMovementLedgerEntity.builder()
                    .movementNumber(newMovementNumber())
                    .movementType(SALE_OUTPUT)
                    .direction(DIR_OUT)
                    .sourceType(sourceType)
                    .sourceId(item.getId())
                    .sourceDocumentNumber(order.getOrderCode())
                    .transactionDate(txDate)
                    .taxPeriod(period)
                    .financialYear(fy)
                    .businessGstin(businessGstin())
                    .counterpartyName(order.getCustomerName())
                    .counterpartyGstin(order.getCustomerGstin())
                    .counterpartyType("CUSTOMER")
                    .orderItemId(item.getId())
                    .productUuid(item.getProductUuid() != null ? item.getProductUuid().toString() : null)
                    .hsnCode(item.getHsnCode())
                    .quantity(item.getQuantity() != null ? item.getQuantity() : 1)
                    .unitPricePaise(nvl(item.getUnitPricePaise()))
                    .taxableValuePaise(taxable)
                    .cgstRateBp(item.getCgstRateBp())
                    .sgstRateBp(item.getSgstRateBp())
                    .igstRateBp(item.getIgstRateBp())
                    .cgstAmountPaise(cgst)
                    .sgstAmountPaise(sgst)
                    .igstAmountPaise(igst)
                    .totalTaxPaise(total)
                    .placeOfSupply(order.getPlaceOfSupply())
                    .sellerStateCode(order.getSellerStateCode())
                    .buyerStateCode(order.getBuyerStateCode())
                    .supplyType(supplyType)
                    .status("POSTED")
                    .organizationId(orgId())
                    .gstRegistrationId(regId())
                    .build());
        }

        if (toSave.isEmpty()) return;
        movementRepo.saveAll(toSave);
        log.info("GST movements recorded for order {}: {} item-level row(s)",
                order.getOrderCode(), toSave.size());
    }

    // ── Core: record a purchase movement ─────────────────────────────────────

    /**
     * Called after a vendor invoice PDF is successfully extracted and GstInputTaxEntity is saved.
     * Idempotent: skips if a movement already exists for this input tax record.
     */
    @Transactional
    public void recordPurchaseMovement(GstInputTaxEntity inputTax) {
        String sourceType = "GST_INPUT_TAX";
        Long sourceId = inputTax.getId();
        if (movementRepo.existsBySourceTypeAndSourceId(sourceType, sourceId)) return;

        long cgst = nvl(inputTax.getCgstAmount());
        long sgst = nvl(inputTax.getSgstAmount());
        long igst = nvl(inputTax.getIgstAmount());
        long total = cgst + sgst + igst;
        if (total == 0) return;

        LocalDate txDate = inputTax.getInvoiceDate() != null ? inputTax.getInvoiceDate() : LocalDate.now();
        String period = inputTax.getTaxPeriod() != null ? inputTax.getTaxPeriod() : taxPeriod(txDate);
        String fy = inputTax.getFinancialYear() != null ? inputTax.getFinancialYear() : financialYear(txDate);

        GstMovementLedgerEntity m = GstMovementLedgerEntity.builder()
                .movementNumber(newMovementNumber())
                .movementType(PURCHASE_INPUT)
                .direction(DIR_IN)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .sourceDocumentNumber(inputTax.getInvoiceNumber())
                .transactionDate(txDate)
                .taxPeriod(period)
                .financialYear(fy)
                .businessGstin(businessGstin())
                .counterpartyGstin(inputTax.getVendorGstin())
                .counterpartyName(inputTax.getVendorName())
                .counterpartyType("VENDOR")
                .taxableValuePaise(nvl(inputTax.getTaxableValue()))
                .cgstAmountPaise(cgst)
                .sgstAmountPaise(sgst)
                .igstAmountPaise(igst)
                .totalTaxPaise(total)
                .itcStatus(inputTax.getItcStatus() != null ? inputTax.getItcStatus() : "PENDING_REVIEW")
                .supplyType(cgst > 0 ? "INTRA_STATE" : "INTER_STATE")
                .status("POSTED")
                .organizationId(orgId())
                .gstRegistrationId(regId())
                .build();

        movementRepo.save(m);
        log.info("GST purchase movement recorded: {} for invoice {}", m.getMovementNumber(), inputTax.getInvoiceNumber());
    }

    // ── Customer returns ─────────────────────────────────────────────────────
    //
    // The old whole-order recordCustomerReturnAdjustment() has been removed.
    // Returns are now item- and quantity-level and live in
    // ReturnProcessingService + GstCreditNoteService, which reverse a
    // proportional share of each line's original snapshot instead of the
    // entire order's aggregate.

    // ── Backfill / migration ─────────────────────────────────────────────────

    /**
     * Migrates the ledger from order-level SALE_OUTPUT rows to item-level ones.
     *
     * Earlier movements were written with sourceType GST_OUTPUT_TAX, one per
     * order. Those are marked SUPERSEDED (never deleted) and replaced with one
     * CUSTOMER_ORDER_ITEM row per order line, so totals stay identical while
     * drill-down becomes possible.
     *
     * Idempotent: rerunning skips lines that already have a movement.
     */
    @Transactional
    public int backfillOutputTax() {
        int created = 0;

        for (CustomerOrderEntity order : customerOrderRepo.findByArchiveFalseOrderByIdAsc()) {
            List<CustomerOrderItemEntity> items =
                    orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(order.getId());
            if (items.isEmpty()) continue;

            int before = countExistingItemMovements(items);
            recordSaleMovement(order, items);
            int after = countExistingItemMovements(items);
            created += (after - before);
        }

        // Retire the superseded order-level rows so the summary does not
        // double-count them alongside the new item-level rows.
        int superseded = 0;
        for (GstMovementLedgerEntity m : movementRepo.findBySourceTypeAndStatus("GST_OUTPUT_TAX", "POSTED")) {
            m.setStatus("SUPERSEDED");
            m.setReason("Superseded by item-level movements during Phase 2 migration");
            movementRepo.save(m);
            superseded++;
        }

        log.info("Backfill output tax: {} item-level movements created, {} order-level movements superseded",
                created, superseded);
        return created;
    }

    private int countExistingItemMovements(List<CustomerOrderItemEntity> items) {
        int n = 0;
        for (CustomerOrderItemEntity i : items) {
            if (i.getId() != null
                    && movementRepo.existsBySourceTypeAndSourceId("CUSTOMER_ORDER_ITEM", i.getId())) n++;
        }
        return n;
    }


    /**
     * Idempotent backfill: creates PURCHASE_INPUT movements for every existing
     * gst_input_tax record that doesn't yet have a movement.
     */
    @Transactional
    public int backfillInputTax() {
        List<GstInputTaxEntity> all = inputTaxRepo.findAllByOrderByCreatedAtDesc();
        int created = 0;
        for (GstInputTaxEntity it : all) {
            String sourceType = "GST_INPUT_TAX";
            if (movementRepo.existsBySourceTypeAndSourceId(sourceType, it.getId())) continue;

            long cgst = nvl(it.getCgstAmount());
            long sgst = nvl(it.getSgstAmount());
            long igst = nvl(it.getIgstAmount());
            long total = cgst + sgst + igst;
            if (total == 0) continue;

            LocalDate txDate = it.getInvoiceDate() != null ? it.getInvoiceDate() : LocalDate.now();

            GstMovementLedgerEntity m = GstMovementLedgerEntity.builder()
                    .movementNumber(newMovementNumber())
                    .movementType(PURCHASE_INPUT)
                    .direction(DIR_IN)
                    .sourceType(sourceType)
                    .sourceId(it.getId())
                    .sourceDocumentNumber(it.getInvoiceNumber())
                    .transactionDate(txDate)
                    .taxPeriod(it.getTaxPeriod())
                    .financialYear(it.getFinancialYear())
                    .businessGstin(businessGstin())
                    .counterpartyGstin(it.getVendorGstin())
                    .counterpartyName(it.getVendorName())
                    .counterpartyType("VENDOR")
                    .taxableValuePaise(nvl(it.getTaxableValue()))
                    .cgstAmountPaise(cgst)
                    .sgstAmountPaise(sgst)
                    .igstAmountPaise(igst)
                    .totalTaxPaise(total)
                    .itcStatus(it.getItcStatus() != null ? it.getItcStatus() : "PENDING_REVIEW")
                    .supplyType(cgst > 0 ? "INTRA_STATE" : "INTER_STATE")
                    .status("POSTED")
                    .createdBy("BACKFILL")
                    .organizationId(orgId())
                    .gstRegistrationId(regId())
                    .build();

            movementRepo.save(m);
            created++;
        }
        log.info("Backfill input tax: {} new movements created", created);
        return created;
    }

    // ── Dashboard summary derived from movement ledger ────────────────────────

    public record MovementSummary(
            String taxPeriod,
            // Input side
            long inputCgst, long inputSgst, long inputIgst, long totalInputGst,
            long inputTaxableValue,
            // Output side
            long outputCgst, long outputSgst, long outputIgst, long totalOutputGst,
            long outputTaxableValue,
            // Adjustments
            long returnAdjCgst, long returnAdjSgst, long returnAdjIgst, long totalReturnAdj,
            // Derived liability (simplified)
            long netOutputCgst, long netOutputSgst, long netOutputIgst, long netOutputGst,
            // ITC
            long eligibleItc, long pendingItc,
            // Cash payable (naive: net output - eligible ITC)
            long cashCgstPayable, long cashSgstPayable, long cashIgstPayable, long totalCashPayable
    ) {}

    public MovementSummary getSummary(String period) {
        long[] out = aggByDirection(DIR_OUT, period);
        long[] in  = aggByDirection(DIR_IN, period);
        long[] adj = aggByMovementType(CUSTOMER_RETURN_OUTPUT_ADJUSTMENT, period);

        long outCgst = out[0]; long outSgst = out[1]; long outIgst = out[2]; long outTot = out[3]; long outTaxable = out[4];
        long inCgst  = in[0];  long inSgst  = in[1];  long inIgst  = in[2];  long inTot  = in[3];  long inTaxable = in[4];
        // adjustments are stored as negatives; flip sign for display
        long adjCgst = -adj[0]; long adjSgst = -adj[1]; long adjIgst = -adj[2]; long adjTot = -adj[3];

        // Net output after adjustments (credit notes)
        long netOutCgst = Math.max(0, outCgst - adjCgst);
        long netOutSgst = Math.max(0, outSgst - adjSgst);
        long netOutIgst = Math.max(0, outIgst - adjIgst);
        long netOut     = netOutCgst + netOutSgst + netOutIgst;

        // ITC from input movements
        long eligibleItc = getEligibleItc(period);
        long pendingItc  = inTot - eligibleItc;

        // Statutory ITC utilization order (simplified for display):
        // IGST ITC → IGST liability first, then CGST
        // CGST ITC → CGST liability
        // SGST ITC → SGST liability
        long igstEligible = getEligibleItcByHead("igst", period);
        long cgstEligible = getEligibleItcByHead("cgst", period);
        long sgstEligible = getEligibleItcByHead("sgst", period);

        long igstUtilized = Math.min(netOutIgst, igstEligible);
        long remainingIgstCredit = igstEligible - igstUtilized;
        long cgstAfterIgst = netOutCgst - Math.min(netOutCgst, cgstEligible + remainingIgstCredit);
        long sgstAfterItc  = Math.max(0, netOutSgst - sgstEligible);
        long igstAfterItc  = Math.max(0, netOutIgst - igstUtilized);

        long cashCgst = Math.max(0, cgstAfterIgst);
        long cashSgst = Math.max(0, sgstAfterItc);
        long cashIgst = Math.max(0, igstAfterItc);

        return new MovementSummary(
                period != null ? period : "ALL",
                inCgst, inSgst, inIgst, inTot, inTaxable,
                outCgst, outSgst, outIgst, outTot, outTaxable,
                adjCgst, adjSgst, adjIgst, adjTot,
                netOutCgst, netOutSgst, netOutIgst, netOut,
                eligibleItc, Math.max(0, pendingItc),
                cashCgst, cashSgst, cashIgst, cashCgst + cashSgst + cashIgst
        );
    }

    // ── Paged movement list ───────────────────────────────────────────────────

    public Page<GstMovementLedgerEntity> getMovements(
            String period, String movementType, String direction, String search,
            boolean includeHistorical, int page, int size) {
        return movementRepo.findFiltered(
                orgId(), period, movementType, direction,
                search != null && search.isBlank() ? null : search,
                includeHistorical,
                PageRequest.of(page, size));
    }

    public Page<GstCreditNoteEntity> getCreditNotes(String period, String search, int page, int size) {
        return creditNoteRepo.findFiltered(
                orgId(), period,
                search != null && search.isBlank() ? null : search,
                PageRequest.of(page, size));
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private long[] aggByDirection(String direction, String period) {
        List<Object[]> rows = movementRepo.sumByDirectionAndPeriod(orgId(), direction, period);
        return toTuple(rows.isEmpty() ? null : rows.get(0));
    }

    private long[] aggByMovementType(String type, String period) {
        List<Object[]> rows = movementRepo.sumByMovementTypeAndPeriod(orgId(), type, period);
        return toTuple(rows.isEmpty() ? null : rows.get(0));
    }

    private long[] toTuple(Object[] row) {
        if (row == null || row.length < 5) return new long[]{0, 0, 0, 0, 0};
        return new long[]{
                toLong(row[0]), toLong(row[1]), toLong(row[2]),
                toLong(row[3]), toLong(row[4])
        };
    }

    private long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return 0L;
    }

    private long getEligibleItc(String period) {
        List<GstInputTaxEntity> inputs = period != null
                ? inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(period)
                : inputTaxRepo.findAllByOrderByCreatedAtDesc();
        return inputs.stream()
                .filter(i -> "ELIGIBLE".equals(i.getItcEligibility()) || "APPROVED".equals(i.getItcStatus()))
                .mapToLong(i -> nvl(i.getTotalInputTax())).sum();
    }

    private long getEligibleItcByHead(String head, String period) {
        List<GstInputTaxEntity> inputs = period != null
                ? inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(period)
                : inputTaxRepo.findAllByOrderByCreatedAtDesc();
        return inputs.stream()
                .filter(i -> "ELIGIBLE".equals(i.getItcEligibility()) || "APPROVED".equals(i.getItcStatus()))
                .mapToLong(i -> switch (head) {
                    case "cgst" -> nvl(i.getEligibleCgst());
                    case "sgst" -> nvl(i.getEligibleSgst());
                    case "igst" -> nvl(i.getEligibleIgst());
                    default -> 0L;
                }).sum();
    }

    private long nvl(Long v) { return v != null ? v : 0L; }
}
