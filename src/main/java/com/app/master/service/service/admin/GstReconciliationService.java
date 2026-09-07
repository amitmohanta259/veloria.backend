package com.app.master.service.service.admin;

import com.app.master.service.core.entity.*;
import com.app.master.service.repository.admin.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds GST records that existing data cannot account for (spec section 57).
 *
 * Nothing here fabricates a missing GST amount. Where a gap can be closed
 * safely from an existing snapshot it is closed; anything ambiguous is reported
 * and left for a human.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstReconciliationService {

    private final CustomerOrderRepository orderRepo;
    private final CustomerOrderItemRepository orderItemRepo;
    private final GstOutputTaxRepository outputTaxRepo;
    private final GstInputTaxRepository inputTaxRepo;
    private final GstCreditNoteRepository creditNoteRepo;
    private final GstMovementLedgerRepository movementRepo;
    private final OrderReturnRequestRepository returnRequestRepo;
    private final OrderReturnItemRepository returnItemRepo;
    private final GstAuditService auditService;

    public record Finding(String category, String reference, String detail, String severity) {}

    /** Read-only sweep. Safe to run at any time. */
    public Map<String, Object> report() {
        List<Finding> findings = new ArrayList<>();

        for (CustomerOrderEntity o : orderRepo.findByArchiveFalseOrderByIdAsc()) {
            if (outputTaxRepo.findByCustomerOrderId(o.getId()).isEmpty()
                    && nvl(o.getTotalTaxAmount()) > 0) {
                findings.add(new Finding("ORDER_WITHOUT_OUTPUT_TAX", o.getOrderCode(),
                        "Order carries " + o.getTotalTaxAmount() + " paise of GST but has no gst_output_tax row",
                        "HIGH"));
            }
            if (o.getPlaceOfSupply() == null || o.getBuyerStateCode() == null) {
                findings.add(new Finding("MISSING_PLACE_OF_SUPPLY", o.getOrderCode(),
                        "buyerStateCode=" + o.getBuyerStateCode() + " placeOfSupply=" + o.getPlaceOfSupply()
                                + " — CGST/SGST vs IGST classification cannot be verified",
                        "HIGH"));
            }
            boolean returned = "RETURNED".equals(o.getStatus()) || "PARTIALLY_RETURNED".equals(o.getStatus());
            if (returned && creditNoteRepo.findByOriginalOrderCodeOrderByIdAsc(o.getOrderCode()).isEmpty()
                    && nvl(o.getTotalTaxAmount()) > 0) {
                findings.add(new Finding("RETURN_WITHOUT_CREDIT_NOTE", o.getOrderCode(),
                        "Order is " + o.getStatus() + " with " + o.getTotalTaxAmount()
                                + " paise of GST but no credit note exists",
                        "HIGH"));
            }
            for (CustomerOrderItemEntity i : orderItemRepo
                    .findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(o.getId())) {
                if (i.getHsnCode() == null || i.getHsnCode().isBlank()) {
                    findings.add(new Finding("MISSING_HSN", o.getOrderCode(),
                            "Order item " + i.getId() + " has no HSN code", "MEDIUM"));
                }
                if (i.getReturnedQuantity() != null && i.getQuantity() != null
                        && i.getReturnedQuantity() > i.getQuantity()) {
                    findings.add(new Finding("RETURN_QUANTITY_EXCEEDED", o.getOrderCode(),
                            "Order item " + i.getId() + " has returnedQuantity "
                                    + i.getReturnedQuantity() + " > quantity " + i.getQuantity(),
                            "HIGH"));
                }
            }
        }

        for (GstInputTaxEntity it : inputTaxRepo.findAllByOrderByCreatedAtDesc()) {
            if (it.getVendorGstin() == null || it.getVendorGstin().isBlank()) {
                findings.add(new Finding("MISSING_VENDOR_GSTIN", it.getInvoiceNumber(),
                        "Input tax row " + it.getId() + " has no vendor GSTIN — ITC cannot be claimed",
                        "HIGH"));
            }
            if ("PENDING_REVIEW".equals(it.getItcEligibility())) {
                findings.add(new Finding("ITC_PENDING_REVIEW", it.getInvoiceNumber(),
                        "Input tax of " + nvl(it.getTotalInputTax()) + " paise awaiting eligibility review",
                        "LOW"));
            }
        }

        Map<String, Long> byCategory = new LinkedHashMap<>();
        findings.forEach(f -> byCategory.merge(f.category(), 1L, Long::sum));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalFindings", findings.size());
        out.put("byCategory", byCategory);
        out.put("findings", findings);
        return out;
    }

    /**
     * Creates return lines for returns that were verified before item-level
     * tracking existed, so the GST they should have reversed becomes visible.
     *
     * The resulting credit notes are deliberately left in PENDING_REVIEW: these
     * are historical reconstructions and a human must confirm them before they
     * count as issued.
     */
    @Transactional
    public Map<String, Object> reconcileHistoricalReturns() {
        List<String> reconstructed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (CustomerOrderEntity order : orderRepo.findByStatusAndArchiveFalseOrderByIdAsc("RETURNED")) {
            if (!creditNoteRepo.findByOriginalOrderCodeOrderByIdAsc(order.getOrderCode()).isEmpty()) {
                skipped.add(order.getOrderCode() + " (credit note already exists)");
                continue;
            }
            if (nvl(order.getTotalTaxAmount()) == 0) {
                skipped.add(order.getOrderCode() + " (no GST on order)");
                continue;
            }

            List<CustomerOrderItemEntity> items =
                    orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(order.getId());
            if (items.isEmpty()) {
                skipped.add(order.getOrderCode() + " (no order items to reconstruct from)");
                continue;
            }

            OrderReturnRequestEntity request = returnRequestRepo
                    .findByOrderCodeAndArchiveFalse(order.getOrderCode())
                    .orElseGet(() -> OrderReturnRequestEntity.builder()
                            .orderCode(order.getOrderCode())
                            .customerId(order.getCustomerId())
                            .returnType("RETURN")
                            .reason("Reconstructed during GST reconciliation")
                            .archive(false)
                            .build());

            if (request.getReturnNumber() == null) {
                request.setReturnNumber("RET-RECON-" + order.getOrderCode());
            }
            request.setStatus("VERIFIED");
            request.setGstAdjustmentRequired(true);
            request.setGstAdjustmentStatus("PENDING_REVIEW");
            request.setGstAdjustmentReason(
                    "Return predates item-level GST tracking; reconstructed from the original order snapshot");
            returnRequestRepo.save(request);

            // Rebuild the return lines from the order's own snapshot. The whole
            // order was marked RETURNED, so every unit is treated as returned.
            for (CustomerOrderItemEntity item : items) {
                if (!returnItemRepo.findByOrderItemIdOrderByIdAsc(item.getId()).isEmpty()) continue;
                int qty = item.getQuantity() != null ? item.getQuantity() : 1;
                returnItemRepo.save(OrderReturnItemEntity.builder()
                        .returnRequestId(request.getId())
                        .orderItemId(item.getId())
                        .productUuid(item.getProductUuid())
                        .hsnCode(item.getHsnCode())
                        .quantity(qty)
                        .returnCondition(item.getReturnCondition())
                        .unitPricePaise(nvl(item.getUnitPricePaise()))
                        .taxableValuePaise(nvl(item.getTaxableValuePaise()) > 0
                                ? nvl(item.getTaxableValuePaise())
                                : nvl(item.getUnitPricePaise()) * qty)
                        .cgstRateBp(item.getCgstRateBp())
                        .sgstRateBp(item.getSgstRateBp())
                        .igstRateBp(item.getIgstRateBp())
                        .cgstAmountPaise(nvl(item.getCgstAmount()))
                        .sgstAmountPaise(nvl(item.getSgstAmount()))
                        .igstAmountPaise(nvl(item.getIgstAmount()))
                        .totalTaxPaise(nvl(item.getCgstAmount()) + nvl(item.getSgstAmount())
                                + nvl(item.getIgstAmount()))
                        .build());
                item.setReturnedQuantity(qty);
                orderItemRepo.save(item);
            }

            auditService.log("RETURN_REQUEST", request.getId(), request.getReturnNumber(),
                    "RETURN_RECONSTRUCTED", null, "RECONCILIATION");
            reconstructed.add(order.getOrderCode());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reconstructed", reconstructed);
        out.put("skipped", skipped);
        out.put("note", "Return lines were rebuilt from the original order snapshots. "
                + "Review each one, then raise its credit note — nothing has been issued automatically.");
        log.info("Historical return reconciliation: {} reconstructed, {} skipped",
                reconstructed.size(), skipped.size());
        return out;
    }

    private long nvl(Long v) { return v != null ? v : 0L; }
}
