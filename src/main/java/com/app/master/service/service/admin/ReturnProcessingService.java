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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Verifies a customer return at item and quantity level.
 *
 * Replaces the previous flow, which set returnCondition on every item in the
 * order and reversed the whole order's GST no matter what actually came back.
 *
 * Two decisions are kept deliberately separate (spec section 11):
 *   - returnCondition (PRODUCT_OK / DAMAGED / LOST) governs inventory only
 *   - gstAdjustmentRequired governs whether a credit note is raised
 * A damaged item can still warrant a credit note; that is a commercial and
 * statutory judgement, not an inventory one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReturnProcessingService {

    private final CustomerOrderRepository orderRepo;
    private final CustomerOrderItemRepository orderItemRepo;
    private final OrderReturnRequestRepository returnRequestRepo;
    private final OrderReturnItemRepository returnItemRepo;
    private final InventoryProductRepository productRepo;
    private final GstRoundingService rounding;
    private final GstCreditNoteService creditNoteService;
    private final GstAuditService auditService;

    /** One returned line as submitted by the admin. */
    public record ReturnLine(Long orderItemId, Integer quantity, String condition) {}

    public record VerifyResult(
            String returnNumber,
            String orderStatus,
            int linesReturned,
            int unitsReturned,
            String gstAdjustmentStatus,
            String creditNoteNumber,
            long taxableReversedPaise,
            long taxReversedPaise
    ) {}

    private String newReturnNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "RET-" + date + "-" + suffix;
    }

    /**
     * Verifies a return.
     *
     * @param lines                  which order items and how many units
     * @param gstAdjustmentRequired  null means "decide by default" (true)
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public VerifyResult verify(String orderCode,
                               List<ReturnLine> lines,
                               String notes,
                               Boolean gstAdjustmentRequired,
                               String gstAdjustmentReason,
                               String performedBy) throws VeloriaException {

        CustomerOrderEntity order = orderRepo.findByOrderCodeAndArchiveFalse(orderCode)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Order not found: " + orderCode));

        List<CustomerOrderItemEntity> orderItems =
                orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(order.getId());
        Map<Long, CustomerOrderItemEntity> byId = new LinkedHashMap<>();
        orderItems.forEach(i -> byId.put(i.getId(), i));

        // No explicit lines: return everything still outstanding. Keeps the old
        // "return the whole order" call working, but now it is quantity-aware.
        List<ReturnLine> effective = (lines == null || lines.isEmpty())
                ? orderItems.stream()
                    .filter(i -> i.remainingReturnableQuantity() > 0)
                    .map(i -> new ReturnLine(i.getId(), i.remainingReturnableQuantity(), null))
                    .toList()
                : lines;

        if (effective.isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Nothing left to return on order " + orderCode);
        }

        validate(effective, byId, orderCode);

        OrderReturnRequestEntity request = resolveRequest(order, performedBy);

        List<OrderReturnItemEntity> returnItems = new ArrayList<>();
        int unitsReturned = 0;

        for (ReturnLine line : effective) {
            CustomerOrderItemEntity item = byId.get(line.orderItemId());
            int qty = line.quantity();
            String condition = line.condition() != null ? line.condition() : "PRODUCT_OK";

            int originalQty = item.getQuantity() != null ? item.getQuantity() : 1;
            int alreadyReturned = returnItemRepo.totalReturnedQuantity(item.getId());

            // Proportional share of the ORIGINAL snapshot. The final return
            // settles the remainder exactly so repeated partial returns can
            // never sum to more (or less) than what was originally charged.
            long taxable = rounding.remainingShare(
                    nvl(item.getTaxableValuePaise()) > 0
                            ? nvl(item.getTaxableValuePaise())
                            : nvl(item.getUnitPricePaise()) * originalQty,
                    qty, originalQty, alreadyReturned, returnItemRepo.totalReversedTaxable(item.getId()));
            long cgst = rounding.remainingShare(nvl(item.getCgstAmount()),
                    qty, originalQty, alreadyReturned, returnItemRepo.totalReversedCgst(item.getId()));
            long sgst = rounding.remainingShare(nvl(item.getSgstAmount()),
                    qty, originalQty, alreadyReturned, returnItemRepo.totalReversedSgst(item.getId()));
            long igst = rounding.remainingShare(nvl(item.getIgstAmount()),
                    qty, originalQty, alreadyReturned, returnItemRepo.totalReversedIgst(item.getId()));

            String productName = productRepo.findByUuid(item.getProductUuid())
                    .map(InventoryProductEntity::getName).orElse(null);

            OrderReturnItemEntity ri = OrderReturnItemEntity.builder()
                    .returnRequestId(request.getId())
                    .orderItemId(item.getId())
                    .productUuid(item.getProductUuid())
                    .productName(productName)
                    .hsnCode(item.getHsnCode())
                    .quantity(qty)
                    .returnCondition(condition)
                    .unitPricePaise(nvl(item.getUnitPricePaise()))
                    .taxableValuePaise(taxable)
                    .cgstRateBp(item.getCgstRateBp())
                    .sgstRateBp(item.getSgstRateBp())
                    .igstRateBp(item.getIgstRateBp())
                    .cgstAmountPaise(cgst)
                    .sgstAmountPaise(sgst)
                    .igstAmountPaise(igst)
                    .totalTaxPaise(cgst + sgst + igst)
                    .build();
            returnItemRepo.save(ri);
            returnItems.add(ri);

            // Inventory-facing state: condition and the running returned count.
            item.setReturnedQuantity(alreadyReturned + qty);
            item.setReturnCondition(condition);
            orderItemRepo.save(item);

            unitsReturned += qty;
        }

        // Order status reflects whether anything is still outstanding.
        boolean fullyReturned = orderItemRepo
                .findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(order.getId())
                .stream().allMatch(i -> i.remainingReturnableQuantity() == 0);
        String newStatus = fullyReturned ? "RETURNED" : "PARTIALLY_RETURNED";
        order.setStatus(newStatus);
        orderRepo.save(order);

        request.setVerificationNotes(notes);
        request.setStatus("VERIFIED");
        request.setVerifiedAt(Instant.now());
        request.setVerifiedBy(performedBy);
        request.setVerificationStatus(effective.get(0).condition() != null
                ? effective.get(0).condition() : "PRODUCT_OK");

        boolean wantsGst = gstAdjustmentRequired == null || gstAdjustmentRequired;
        request.setGstAdjustmentRequired(wantsGst);
        request.setGstAdjustmentReason(gstAdjustmentReason);

        String creditNoteNumber = null;
        long taxableReversed = 0L;
        long taxReversed = 0L;

        if (wantsGst) {
            try {
                Optional<GstCreditNoteEntity> cn =
                        creditNoteService.createForReturn(order, request, returnItems, performedBy);
                if (cn.isPresent()) {
                    creditNoteNumber = cn.get().getCreditNoteNumber();
                    taxableReversed = nvl(cn.get().getTaxableValuePaise());
                    taxReversed = nvl(cn.get().getTotalCreditPaise());
                    request.setCreditNoteId(cn.get().getId());
                    request.setGstAdjustmentStatus("COMPLETED");
                } else {
                    request.setGstAdjustmentStatus("NOT_APPLICABLE");
                    request.setGstAdjustmentReason("No GST was charged on the returned lines");
                }
            } catch (Exception e) {
                // The physical return is already recorded; surface the GST gap
                // instead of pretending the whole operation succeeded.
                request.setGstAdjustmentStatus("FAILED");
                auditService.recordException("RETURN_CREDIT_NOTE", "RETURN_REQUEST",
                        request.getId(), request.getReturnNumber(), e);
            }
        } else {
            request.setGstAdjustmentStatus("NOT_APPLICABLE");
        }

        returnRequestRepo.save(request);

        auditService.log("RETURN_REQUEST", request.getId(), request.getReturnNumber(),
                "RETURN_VERIFIED", null, performedBy);

        log.info("Return {} verified for order {}: {} line(s), {} unit(s), status {}, GST {}",
                request.getReturnNumber(), orderCode, returnItems.size(), unitsReturned,
                newStatus, request.getGstAdjustmentStatus());

        return new VerifyResult(request.getReturnNumber(), newStatus, returnItems.size(),
                unitsReturned, request.getGstAdjustmentStatus(), creditNoteNumber,
                taxableReversed, taxReversed);
    }

    /**
     * Enforces the return-quantity invariants before anything is written
     * (spec section 51).
     */
    private void validate(List<ReturnLine> lines,
                          Map<Long, CustomerOrderItemEntity> byId,
                          String orderCode) throws VeloriaException {
        Map<Long, Integer> requestedPerItem = new LinkedHashMap<>();

        for (ReturnLine line : lines) {
            if (line.orderItemId() == null) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST, "orderItemId is required for every return line");
            }
            CustomerOrderItemEntity item = byId.get(line.orderItemId());
            if (item == null) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Order item " + line.orderItemId() + " does not belong to order " + orderCode);
            }
            if (line.quantity() == null || line.quantity() <= 0) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Return quantity must be greater than zero for order item " + line.orderItemId());
            }
            if (line.condition() != null
                    && !List.of("PRODUCT_OK", "DAMAGED", "LOST").contains(line.condition())) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Invalid condition '" + line.condition() + "'. Must be PRODUCT_OK, DAMAGED or LOST");
            }
            requestedPerItem.merge(line.orderItemId(), line.quantity(), Integer::sum);
        }

        for (Map.Entry<Long, Integer> e : requestedPerItem.entrySet()) {
            CustomerOrderItemEntity item = byId.get(e.getKey());
            int remaining = item.remainingReturnableQuantity();
            if (e.getValue() > remaining) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Cannot return " + e.getValue() + " unit(s) of order item " + e.getKey()
                                + ": only " + remaining + " of " + item.getQuantity()
                                + " remain returnable (" + item.getReturnedQuantity() + " already returned)");
            }
        }
    }

    /**
     * Finds the customer's open return request for this order, or creates one
     * when the admin is recording a return the customer never raised.
     * A new request is created for each return event so that repeat returns
     * each get their own credit note.
     */
    private OrderReturnRequestEntity resolveRequest(CustomerOrderEntity order, String performedBy) {
        Optional<OrderReturnRequestEntity> open =
                returnRequestRepo.findByOrderCodeAndArchiveFalseOrderByIdAsc(order.getOrderCode())
                        .stream()
                        .filter(r -> r.getReturnNumber() == null || !"VERIFIED".equals(r.getStatus()))
                        .findFirst();

        OrderReturnRequestEntity request = open.orElseGet(() -> OrderReturnRequestEntity.builder()
                .orderCode(order.getOrderCode())
                .customerId(order.getCustomerId())
                .returnType("RETURN")
                .reason("Recorded by admin")
                .archive(false)
                .build());

        if (request.getReturnNumber() == null) {
            request.setReturnNumber(newReturnNumber());
        }
        return returnRequestRepo.save(request);
    }

    private long nvl(Long v) { return v != null ? v : 0L; }
}
