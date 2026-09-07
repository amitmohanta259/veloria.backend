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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Exchange and replacement (spec phases 14 and 15).
 *
 * An exchange is deliberately NOT an edit of the original order. It is two
 * accounting events:
 *
 *   1. the returned item goes through the existing return and credit-note flow,
 *      which reverses exactly that item's GST from its own snapshot; and
 *   2. the replacement is a fresh supply, priced and taxed on its own merits.
 *
 * The original invoice is never modified. The difference between the two sides
 * is computed by the backend and settled in one direction or the other.
 *
 * REPLACEMENT is the special case where the replacement is the same product at
 * the same value; the difference is then zero, but both events are still
 * recorded so the ledger and the returns stay correct.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderExchangeService {

    public static final String EXCHANGE    = "EXCHANGE";
    public static final String REPLACEMENT = "REPLACEMENT";

    public static final String INITIATED = "INITIATED";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    public static final String COLLECT_FROM_CUSTOMER = "COLLECT_FROM_CUSTOMER";
    public static final String REFUND_TO_CUSTOMER    = "REFUND_TO_CUSTOMER";
    public static final String NO_SETTLEMENT         = "NONE";

    private final OrderExchangeRequestRepository exchangeRepo;
    private final OrderExchangeItemRepository exchangeItemRepo;
    private final CustomerOrderRepository orderRepo;
    private final CustomerOrderItemRepository orderItemRepo;
    private final InventoryProductRepository productRepo;
    private final SalesInvoiceRepository invoiceRepo;
    private final ReturnProcessingService returnService;
    private final GstCalculationService calculationService;
    private final GstIdentityService identityService;
    private final GstTaxPeriodService periodService;
    private final GstAuditService auditService;
    private final com.app.master.service.core.security.GstSecurityContext securityContext;

    /** What the customer is sending back. */
    public record ReturnLine(Long orderItemId, Integer quantity, String condition) {}

    /** What the customer is receiving instead. */
    public record ReplacementLine(UUID productUuid, Integer quantity, Long unitPricePaise) {}

    public record ExchangeResult(
            String exchangeNumber,
            String exchangeType,
            String status,
            String creditNoteNumber,
            long returnedTaxablePaise, long returnedTaxPaise,
            long replacementTaxablePaise, long replacementTaxPaise,
            long taxableDifferencePaise, long taxDifferencePaise, long paymentDifferencePaise,
            String settlementDirection
    ) {}

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

    /**
     * Records an exchange.
     *
     * The returned side reuses {@link ReturnProcessingService}, so quantity
     * validation, the proportional GST reversal and the credit note all behave
     * exactly as they do for an ordinary return — including refusing to return
     * more than was bought, and never reversing the same units twice.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public ExchangeResult exchange(String orderCode,
                                    List<ReturnLine> returnLines,
                                    List<ReplacementLine> replacementLines,
                                    String exchangeType,
                                    String reason) throws VeloriaException {

        CustomerOrderEntity order = orderRepo.findByOrderCodeAndArchiveFalse(orderCode)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Order not found: " + orderCode));

        if (returnLines == null || returnLines.isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "An exchange needs at least one returned item");
        }
        if (replacementLines == null || replacementLines.isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "An exchange needs at least one replacement item. "
                            + "To return goods without a replacement, use the returns flow.");
        }

        String period = YearMonth.now().toString();
        periodService.assertOpen(period);

        String type = REPLACEMENT.equals(exchangeType) ? REPLACEMENT : EXCHANGE;

        OrderExchangeRequestEntity request = exchangeRepo.save(OrderExchangeRequestEntity.builder()
                .exchangeNumber(newExchangeNumber(type))
                .exchangeType(type)
                .originalOrderId(order.getId())
                .originalOrderCode(orderCode)
                .originalInvoiceId(invoiceRepo.findActiveForOrder(order.getId()).stream()
                        .findFirst().map(SalesInvoiceEntity::getId).orElse(null))
                .customerId(order.getCustomerId())
                .customerName(order.getCustomerName())
                .status(INITIATED)
                .reason(reason)
                .taxPeriod(period)
                .organizationId(orgId())
                .gstRegistrationId(regId())
                .createdBy(securityContext.actor())
                .build());

        // ── Returned side: the existing return flow does the work ──
        // This is what prevents double reversal and excess returns: the
        // quantity guards and the credit note live there, not here.
        ReturnProcessingService.VerifyResult returned = returnService.verify(
                orderCode,
                returnLines.stream()
                        .map(l -> new ReturnProcessingService.ReturnLine(
                                l.orderItemId(), l.quantity(), l.condition()))
                        .toList(),
                "Exchange " + request.getExchangeNumber() + (reason != null ? " — " + reason : ""),
                true,
                "Goods returned as part of exchange " + request.getExchangeNumber(),
                securityContext.actor());

        recordReturnedItems(request, returnLines);

        // ── Replacement side: a fresh supply, taxed on its own value ──
        String sellerState = identityService.sellerStateCode();
        String placeOfSupply = order.getPlaceOfSupply() != null
                ? order.getPlaceOfSupply() : order.getBuyerStateCode();

        long replacementTaxable = 0, replacementTax = 0;
        List<OrderExchangeItemEntity> replacementItems = new ArrayList<>();

        for (ReplacementLine line : replacementLines) {
            if (line.quantity() == null || line.quantity() <= 0) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Replacement quantity must be greater than zero");
            }
            InventoryProductEntity product = productRepo.findByUuid(line.productUuid())
                    .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST,
                            "Replacement product not found: " + line.productUuid()));

            // The price comes from the product master unless explicitly given;
            // a client-supplied tax amount is never accepted.
            long unitPrice = line.unitPricePaise() != null && line.unitPricePaise() > 0
                    ? line.unitPricePaise()
                    : (product.getSellingPrice() != null ? product.getSellingPrice() : 0L);

            GstCalculationService.GstResult gst = calculationService.calculate(
                    product.getHsnCode(), unitPrice, line.quantity(),
                    placeOfSupply, sellerState, LocalDate.now());

            replacementItems.add(exchangeItemRepo.save(OrderExchangeItemEntity.builder()
                    .exchangeRequestId(request.getId())
                    .side("REPLACEMENT")
                    .productUuid(product.getUuid())
                    .productName(product.getName())
                    .hsnCode(product.getHsnCode())
                    .quantity(line.quantity())
                    .unitPricePaise(unitPrice)
                    .taxableValuePaise(gst.taxableValuePaise())
                    .cgstAmountPaise(gst.cgstAmount())
                    .sgstAmountPaise(gst.sgstAmount())
                    .igstAmountPaise(gst.igstAmount())
                    .cessAmountPaise(gst.cessAmount())
                    .totalTaxPaise(gst.totalTax())
                    .build()));

            replacementTaxable += gst.taxableValuePaise();
            replacementTax += gst.totalTax();
        }

        // ── Difference and settlement ──
        long returnedTaxable = returned.taxableReversedPaise();
        long returnedTax = returned.taxReversedPaise();
        long taxableDiff = replacementTaxable - returnedTaxable;
        long taxDiff = replacementTax - returnedTax;
        long paymentDiff = taxableDiff + taxDiff;

        String settlement = paymentDiff > 0 ? COLLECT_FROM_CUSTOMER
                : paymentDiff < 0 ? REFUND_TO_CUSTOMER
                : NO_SETTLEMENT;

        request.setReturnedTaxablePaise(returnedTaxable);
        request.setReturnedTaxPaise(returnedTax);
        request.setReplacementTaxablePaise(replacementTaxable);
        request.setReplacementTaxPaise(replacementTax);
        request.setTaxableDifferencePaise(taxableDiff);
        request.setTaxDifferencePaise(taxDiff);
        request.setPaymentDifferencePaise(paymentDiff);
        request.setSettlementDirection(settlement);
        request.setStatus(COMPLETED);
        request.setCompletedAt(Instant.now());
        exchangeRepo.save(request);

        auditService.log("ORDER_EXCHANGE", request.getId(), request.getExchangeNumber(),
                type.equals(REPLACEMENT) ? "REPLACEMENT_COMPLETED" : "EXCHANGE_COMPLETED",
                period, securityContext.actor());

        log.info("{} {} on order {}: returned {} paise tax, replacement {} paise tax, settlement {} {}",
                type, request.getExchangeNumber(), orderCode, returnedTax, replacementTax,
                settlement, paymentDiff);

        return new ExchangeResult(
                request.getExchangeNumber(), type, request.getStatus(),
                returned.creditNoteNumber(),
                returnedTaxable, returnedTax,
                replacementTaxable, replacementTax,
                taxableDiff, taxDiff, paymentDiff, settlement);
    }

    /** Snapshots the returned side onto the exchange for reporting. */
    private void recordReturnedItems(OrderExchangeRequestEntity request, List<ReturnLine> lines) {
        for (ReturnLine line : lines) {
            orderItemRepo.findById(line.orderItemId()).ifPresent(item -> {
                int qty = line.quantity();
                int originalQty = item.getQuantity() != null ? item.getQuantity() : 1;
                exchangeItemRepo.save(OrderExchangeItemEntity.builder()
                        .exchangeRequestId(request.getId())
                        .side("RETURNED")
                        .orderItemId(item.getId())
                        .productUuid(item.getProductUuid())
                        .productName(productRepo.findByUuid(item.getProductUuid())
                                .map(InventoryProductEntity::getName).orElse(null))
                        .hsnCode(item.getHsnCode())
                        .quantity(qty)
                        .unitPricePaise(nvl(item.getUnitPricePaise()))
                        .taxableValuePaise(share(nvl(item.getTaxableValuePaise()), qty, originalQty))
                        .cgstAmountPaise(share(nvl(item.getCgstAmount()), qty, originalQty))
                        .sgstAmountPaise(share(nvl(item.getSgstAmount()), qty, originalQty))
                        .igstAmountPaise(share(nvl(item.getIgstAmount()), qty, originalQty))
                        .totalTaxPaise(share(nvl(item.getCgstAmount()) + nvl(item.getSgstAmount())
                                + nvl(item.getIgstAmount()), qty, originalQty))
                        .build());
            });
        }
    }

    public List<OrderExchangeItemEntity> itemsOf(Long exchangeId) {
        return exchangeItemRepo.findByExchangeRequestIdOrderByIdAsc(exchangeId);
    }

    public List<OrderExchangeRequestEntity> list() {
        return exchangeRepo.findByOrganizationIdOrderByIdDesc(orgId());
    }

    public List<OrderExchangeRequestEntity> forOrder(Long orderId) {
        return exchangeRepo.findByOriginalOrderIdOrderByIdAsc(orderId);
    }

    private String newExchangeNumber(String type) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return (REPLACEMENT.equals(type) ? "RPL-" : "EXC-") + date + "-" + suffix;
    }

    private static long share(long amount, int qty, int originalQty) {
        if (originalQty <= 0) return 0;
        if (qty >= originalQty) return amount;
        return Math.round((double) amount * qty / originalQty);
    }

    private static long nvl(Long v) { return v != null ? v : 0L; }
}
