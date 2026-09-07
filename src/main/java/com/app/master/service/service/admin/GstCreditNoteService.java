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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds GST credit notes from verified return lines.
 *
 * This replaces the previous whole-order reversal. The GST reversed is the sum
 * of the per-line proportional shares of each original order item's stored
 * snapshot — so returning 2 of 5 units reverses exactly 2 units' worth of tax,
 * and the tax rate used is the one that applied on the original invoice date.
 *
 * Credit notes are created in PENDING_REVIEW. A human transitions them to
 * APPROVED and then ISSUED; nothing here claims a note has been reported to
 * the GST portal.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstCreditNoteService {

    private final GstCreditNoteRepository creditNoteRepo;
    private final GstCreditNoteItemRepository creditNoteItemRepo;
    private final GstMovementLedgerRepository movementRepo;
    private final OrderReturnItemRepository returnItemRepo;
    private final GstOutputTaxRepository outputTaxRepo;
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

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // ── Numbering ────────────────────────────────────────────────────────────

    private String newCreditNoteNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "CN-" + date + "-" + suffix;
    }

    private String newMovementNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "GST-MOV-" + date + "-" + suffix;
    }

    private String taxPeriod(LocalDate d) { return YearMonth.from(d).toString(); }

    private String financialYear(LocalDate d) {
        int yr = d.getYear();
        return d.getMonthValue() >= 4
                ? yr + "-" + String.format("%02d", (yr + 1) % 100)
                : (yr - 1) + "-" + String.format("%02d", yr % 100);
    }

    /**
     * Creates the credit note and its GST adjustment movement for one verified
     * return event.
     *
     * Idempotent on returnRequestId — calling twice returns the existing note
     * rather than reversing GST a second time. The database also enforces this
     * with a unique constraint, so a concurrent duplicate fails rather than
     * double-reversing.
     *
     * @return the credit note, or empty when the return carries no GST
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public Optional<GstCreditNoteEntity> createForReturn(
            CustomerOrderEntity order,
            OrderReturnRequestEntity returnRequest,
            List<OrderReturnItemEntity> returnItems,
            String performedBy) {

        Optional<GstCreditNoteEntity> existing = creditNoteRepo.findByReturnRequestId(returnRequest.getId());
        if (existing.isPresent()) {
            log.info("Credit note {} already exists for return {}, skipping",
                    existing.get().getCreditNoteNumber(), returnRequest.getId());
            return existing;
        }

        long taxable = returnItems.stream().mapToLong(i -> nvl(i.getTaxableValuePaise())).sum();
        long cgst = returnItems.stream().mapToLong(i -> nvl(i.getCgstAmountPaise())).sum();
        long sgst = returnItems.stream().mapToLong(i -> nvl(i.getSgstAmountPaise())).sum();
        long igst = returnItems.stream().mapToLong(i -> nvl(i.getIgstAmountPaise())).sum();
        long totalCredit = cgst + sgst + igst;

        if (totalCredit == 0 && taxable == 0) {
            log.info("Return {} carries no GST — no credit note created", returnRequest.getReturnNumber());
            return Optional.empty();
        }

        // Original invoice period stays untouched; the adjustment lands in the
        // period the credit note is issued in (spec sections 17 and 18).
        LocalDate originalDate = order.getOrderPlacedAt() != null
                ? order.getOrderPlacedAt().atZone(IST).toLocalDate()
                : LocalDate.now();
        LocalDate today = LocalDate.now();

        String originalPeriod = taxPeriod(originalDate);
        String adjustmentPeriod = taxPeriod(today);
        String adjustmentFy = financialYear(today);

        String supplyType = resolveSupplyType(order);

        GstCreditNoteEntity cn = GstCreditNoteEntity.builder()
                .creditNoteNumber(newCreditNoteNumber())
                .originalOrderCode(order.getOrderCode())
                .originalOrderId(order.getId())
                .originalInvoiceDate(originalDate)
                .returnRequestId(returnRequest.getId())
                .customerId(order.getCustomerId())
                .customerName(order.getCustomerName())
                .customerGstin(order.getCustomerGstin())
                .taxPeriod(adjustmentPeriod)
                .financialYear(adjustmentFy)
                .originalTaxPeriod(originalPeriod)
                .creditNoteDate(today)
                .taxableValuePaise(taxable)
                .cgstPaise(cgst)
                .sgstPaise(sgst)
                .igstPaise(igst)
                .totalCreditPaise(totalCredit)
                .supplyType(supplyType)
                .placeOfSupply(order.getPlaceOfSupply())
                .condition(returnRequest.getVerificationStatus())
                .reasonCode("CUSTOMER_RETURN")
                .status("PENDING_REVIEW")
                .reportingStatus("NOT_REPORTED")
                .notes(returnRequest.getVerificationNotes())
                .createdBy(performedBy)
                .organizationId(orgId())
                .gstRegistrationId(regId())
                .build();
        creditNoteRepo.save(cn);

        for (OrderReturnItemEntity ri : returnItems) {
            creditNoteItemRepo.save(GstCreditNoteItemEntity.builder()
                    .creditNoteId(cn.getId())
                    .orderItemId(ri.getOrderItemId())
                    .returnItemId(ri.getId())
                    .productUuid(ri.getProductUuid())
                    .productName(ri.getProductName())
                    .hsnCode(ri.getHsnCode())
                    .quantity(ri.getQuantity())
                    .unitPricePaise(ri.getUnitPricePaise())
                    .taxableValuePaise(ri.getTaxableValuePaise())
                    .cgstRateBp(ri.getCgstRateBp())
                    .sgstRateBp(ri.getSgstRateBp())
                    .igstRateBp(ri.getIgstRateBp())
                    .cgstAmountPaise(ri.getCgstAmountPaise())
                    .sgstAmountPaise(ri.getSgstAmountPaise())
                    .igstAmountPaise(ri.getIgstAmountPaise())
                    .totalTaxPaise(ri.getTotalTaxPaise())
                    .build());
        }

        // One ADJUSTMENT movement per returned line, so the ledger stays
        // item-level and drill-down can answer "which product, which HSN".
        List<GstMovementLedgerEntity> movements = new ArrayList<>();
        for (OrderReturnItemEntity ri : returnItems) {
            Long originalMovementId = movementRepo
                    .findBySourceTypeAndSourceId("CUSTOMER_ORDER_ITEM", ri.getOrderItemId())
                    .map(GstMovementLedgerEntity::getId)
                    .orElse(null);

            movements.add(GstMovementLedgerEntity.builder()
                    .movementNumber(newMovementNumber())
                    .movementType(GstMovementService.CUSTOMER_RETURN_OUTPUT_ADJUSTMENT)
                    .direction(GstMovementService.DIR_ADJUSTMENT)
                    .sourceType("CREDIT_NOTE_ITEM")
                    .sourceId(ri.getId())
                    .sourceDocumentNumber(cn.getCreditNoteNumber())
                    .originalDocumentId(order.getId())
                    .originalDocumentNumber(order.getOrderCode())
                    .transactionDate(today)
                    .taxPeriod(adjustmentPeriod)
                    .financialYear(adjustmentFy)
                    .businessGstin(identityService.businessGstin())
                    .counterpartyName(order.getCustomerName())
                    .counterpartyGstin(order.getCustomerGstin())
                    .counterpartyType("CUSTOMER")
                    .orderItemId(ri.getOrderItemId())
                    .productUuid(ri.getProductUuid() != null ? ri.getProductUuid().toString() : null)
                    .productName(ri.getProductName())
                    .hsnCode(ri.getHsnCode())
                    .quantity(ri.getQuantity())
                    .returnedQuantity(ri.getQuantity())
                    .unitPricePaise(ri.getUnitPricePaise())
                    // Negative: this reduces output liability.
                    .taxableValuePaise(-nvl(ri.getTaxableValuePaise()))
                    .cgstRateBp(ri.getCgstRateBp())
                    .sgstRateBp(ri.getSgstRateBp())
                    .igstRateBp(ri.getIgstRateBp())
                    .cgstAmountPaise(-nvl(ri.getCgstAmountPaise()))
                    .sgstAmountPaise(-nvl(ri.getSgstAmountPaise()))
                    .igstAmountPaise(-nvl(ri.getIgstAmountPaise()))
                    .totalTaxPaise(-nvl(ri.getTotalTaxPaise()))
                    .placeOfSupply(order.getPlaceOfSupply())
                    .sellerStateCode(order.getSellerStateCode())
                    .buyerStateCode(order.getBuyerStateCode())
                    .supplyType(supplyType)
                    .referenceTransactionId(originalMovementId)
                    .referenceCreditNoteId(cn.getId())
                    .referenceReturnId(returnRequest.getId())
                    .status("POSTED")
                    .organizationId(orgId())
                    .gstRegistrationId(regId())
                    .createdBy(performedBy)
                    .reason("Customer return " + returnRequest.getReturnNumber()
                            + " — " + ri.getQuantity() + " unit(s) of " + ri.getProductName())
                    .build());
        }
        movementRepo.saveAll(movements);

        cn.setMovementId(movements.isEmpty() ? null : movements.get(0).getId());
        creditNoteRepo.save(cn);

        markOutputTaxReturnStatus(order);

        auditService.log("GST_CREDIT_NOTE", cn.getId(), cn.getCreditNoteNumber(),
                "CREDIT_NOTE_CREATED", adjustmentPeriod, performedBy);

        log.info("Credit note {} created for order {} return {}: {} line(s), taxable {} paise, tax {} paise, period {}→{}",
                cn.getCreditNoteNumber(), order.getOrderCode(), returnRequest.getReturnNumber(),
                returnItems.size(), taxable, totalCredit, originalPeriod, adjustmentPeriod);

        return Optional.of(cn);
    }

    /**
     * Marks the original output tax row FULLY_RETURNED or PARTIALLY_RETURNED
     * based on how much of the order has actually come back.
     */
    private void markOutputTaxReturnStatus(CustomerOrderEntity order) {
        outputTaxRepo.findByCustomerOrderId(order.getId()).ifPresent(ot -> {
            long reversedTax = creditNoteRepo.findByOriginalOrderCodeOrderByIdAsc(order.getOrderCode())
                    .stream().mapToLong(c -> nvl(c.getTotalCreditPaise())).sum();
            long originalTax = nvl(ot.getTotalOutputTax());
            String status = originalTax > 0 && reversedTax >= originalTax
                    ? "FULLY_RETURNED" : "PARTIALLY_RETURNED";
            ot.setReturnStatus(status);
            outputTaxRepo.save(ot);
        });
    }

    private String resolveSupplyType(CustomerOrderEntity order) {
        try {
            return identityService.supplyType(order.getPlaceOfSupply(), order.getSellerStateCode());
        } catch (IllegalStateException unknown) {
            // Historical orders may predate place-of-supply resolution. Fall back
            // to what the original invoice actually charged.
            return nvl(order.getIgstAmount()) > 0 ? "INTER_STATE" : "INTRA_STATE";
        }
    }

    // ── Lifecycle transitions (spec section 16) ──────────────────────────────

    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstCreditNoteEntity approve(Long creditNoteId, String approvedBy) throws VeloriaException {
        GstCreditNoteEntity cn = creditNoteRepo.findById(creditNoteId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Credit note not found: " + creditNoteId));
        requireStatus(cn, "PENDING_REVIEW", "DRAFT");
        String old = cn.getStatus();
        cn.setStatus("APPROVED");
        cn.setApprovedBy(approvedBy);
        cn.setApprovedAt(Instant.now());
        creditNoteRepo.save(cn);
        auditService.log("GST_CREDIT_NOTE", cn.getId(), cn.getCreditNoteNumber(),
                "CREDIT_NOTE_APPROVED", "status", old, "APPROVED", cn.getTaxPeriod(), approvedBy);
        return cn;
    }

    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstCreditNoteEntity issue(Long creditNoteId, String issuedBy) throws VeloriaException {
        GstCreditNoteEntity cn = creditNoteRepo.findById(creditNoteId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Credit note not found: " + creditNoteId));
        requireStatus(cn, "APPROVED");
        String old = cn.getStatus();
        cn.setStatus("ISSUED");
        cn.setIssuedAt(Instant.now());
        creditNoteRepo.save(cn);
        auditService.log("GST_CREDIT_NOTE", cn.getId(), cn.getCreditNoteNumber(),
                "CREDIT_NOTE_ISSUED", "status", old, "ISSUED", cn.getTaxPeriod(), issuedBy);
        return cn;
    }

    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstCreditNoteEntity cancel(Long creditNoteId, String reason, String cancelledBy) throws VeloriaException {
        GstCreditNoteEntity cn = creditNoteRepo.findById(creditNoteId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Credit note not found: " + creditNoteId));
        if ("REPORTED".equals(cn.getStatus()) || "RECONCILED".equals(cn.getStatus())) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "Credit note " + cn.getCreditNoteNumber() + " is already " + cn.getStatus()
                            + " and cannot be cancelled; issue a correcting document instead");
        }
        String old = cn.getStatus();
        cn.setStatus("CANCELLED");
        cn.setNotes(reason);
        creditNoteRepo.save(cn);

        // Reverse the adjustment movements so the ledger nets to zero rather
        // than deleting history.
        movementRepo.findByReferenceCreditNoteIdOrderByIdAsc(cn.getId()).forEach(m -> {
            m.setStatus("CANCELLED");
            movementRepo.save(m);
        });

        auditService.log("GST_CREDIT_NOTE", cn.getId(), cn.getCreditNoteNumber(),
                "CREDIT_NOTE_CANCELLED", "status", old, "CANCELLED", cn.getTaxPeriod(), cancelledBy);
        return cn;
    }

    public List<GstCreditNoteItemEntity> itemsOf(Long creditNoteId) {
        return creditNoteItemRepo.findByCreditNoteIdOrderByIdAsc(creditNoteId);
    }

    private void requireStatus(GstCreditNoteEntity cn, String... allowed) throws VeloriaException {
        for (String s : allowed) {
            if (s.equals(cn.getStatus())) return;
        }
        throw new VeloriaException(ResponseCode.BAD_REQUEST, "Credit note " + cn.getCreditNoteNumber()
                + " is " + cn.getStatus() + "; expected one of " + String.join(", ", allowed));
    }

    private long nvl(Long v) { return v != null ? v : 0L; }
}
