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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The sales invoice layer (spec phases 6 and 8-13).
 *
 * Separates the GST document from the order. The order is the commercial
 * transaction; the invoice is the accounting document that feeds the ledger and
 * the returns.
 *
 * Every amount is recalculated on the backend from the order's own snapshot and
 * the tax rules that applied on the invoice date. No monetary value supplied by
 * a caller is trusted (spec phase 30).
 *
 * Once ISSUED, an invoice is immutable: later changes to product prices, tax
 * rules, addresses or GSTINs cannot alter it. Corrections go through
 * cancellation or amendment, both of which preserve the original.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SalesInvoiceService {

    public static final String DRAFT     = "DRAFT";
    public static final String ISSUED    = "ISSUED";
    public static final String CANCELLED = "CANCELLED";
    public static final String AMENDED   = "AMENDED";

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final SalesInvoiceRepository invoiceRepo;
    private final SalesInvoiceItemRepository itemRepo;
    private final CustomerOrderRepository orderRepo;
    private final CustomerOrderItemRepository orderItemRepo;
    private final InventoryProductRepository productRepo;
    private final GstMovementLedgerRepository movementRepo;
    private final GstInvoiceNumberService numberService;
    private final GstCalculationService calculationService;
    private final GstRoundingService rounding;
    private final GstIdentityService identityService;
    private final GstConfigurationService configService;
    private final GstTaxPeriodService periodService;
    private final GstAuditService auditService;
    private final com.app.master.service.core.security.GstSecurityContext securityContext;

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

    // ── Draft ────────────────────────────────────────────────────────────────

    /**
     * Builds a DRAFT invoice for an order.
     *
     * Idempotent: an order that already has a live invoice returns it rather
     * than producing a second one.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public SalesInvoiceEntity createDraft(String orderCode) throws VeloriaException {
        CustomerOrderEntity order = orderRepo.findByOrderCodeAndArchiveFalse(orderCode)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Order not found: " + orderCode));

        List<SalesInvoiceEntity> live = invoiceRepo.findActiveForOrder(order.getId());
        if (!live.isEmpty()) {
            log.info("Order {} already has invoice {}, returning it",
                    orderCode, live.get(0).getInvoiceNumber());
            return live.get(0);
        }

        // A B2B supply without a valid GSTIN must not become an invoice, and must
        // not be silently downgraded to B2C (spec phase 5). Checked before the
        // lines, because it is a header fact and gives the clearer error.
        if ("B2B".equals(order.getCustomerType())) {
            if (order.getCustomerGstin() == null || order.getCustomerGstin().isBlank()) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Order " + orderCode + " is B2B but carries no customer GSTIN");
            }
            if (!identityService.isValidGstin(order.getCustomerGstin())) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Customer GSTIN on order " + orderCode + " is not valid; "
                                + "correct it or record the order as B2C");
            }
        }

        List<CustomerOrderItemEntity> orderItems =
                orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(order.getId());
        if (orderItems.isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Order " + orderCode + " has no items to invoice");
        }

        LocalDate invoiceDate = order.getOrderPlacedAt() != null
                ? order.getOrderPlacedAt().atZone(IST).toLocalDate() : LocalDate.now();
        String taxPeriod = YearMonth.from(invoiceDate).toString();
        periodService.assertOpen(taxPeriod);

        String sellerState = identityService.sellerStateCode();
        String placeOfSupply = resolvePlaceOfSupply(order);
        String supplyType = safeSupplyType(placeOfSupply, sellerState, order);

        SalesInvoiceEntity invoice = invoiceRepo.save(SalesInvoiceEntity.builder()
                .invoiceDate(invoiceDate)
                .organizationId(orgId())
                .gstRegistrationId(regId())
                .orderId(order.getId())
                .orderCode(order.getOrderCode())
                .customerId(order.getCustomerId())
                .customerType(order.getCustomerType() != null ? order.getCustomerType() : "B2C")
                .customerGstin(order.getCustomerGstin())
                .customerName(order.getCustomerName())
                .customerLegalName(order.getCustomerLegalName())
                .billingAddressSnapshot(order.getDeliveryLocation())
                .shippingAddressSnapshot(order.getDeliveryLocation())
                .placeOfSupply(placeOfSupply)
                .sellerGstin(identityService.businessGstin())
                .sellerStateCode(sellerState)
                .supplyType(supplyType)
                .currency(order.getCurrency() != null ? order.getCurrency() : "INR")
                .status(DRAFT)
                .taxPeriod(taxPeriod)
                .financialYear(GstInvoiceNumberService.financialYear(invoiceDate))
                .createdBy(securityContext.actor())
                .build());

        Totals totals = buildLines(invoice, order, orderItems, invoiceDate, placeOfSupply, sellerState);
        applyTotals(invoice, order, totals);
        invoiceRepo.save(invoice);

        auditService.log("SALES_INVOICE", invoice.getId(), orderCode, "INVOICE_DRAFTED",
                taxPeriod, securityContext.actor());
        log.info("Draft invoice for order {}: {} line(s), taxable {} paise, tax {} paise",
                orderCode, orderItems.size(), totals.taxable, totals.totalTax());
        return invoice;
    }

    // ── Line construction ────────────────────────────────────────────────────

    private static final class Totals {
        long gross, discount, taxable, cgst, sgst, igst, cess, shippingTaxable;
        long totalTax() { return cgst + sgst + igst + cess; }
    }

    /**
     * Builds the invoice lines.
     *
     * Order of calculation (spec phase 11):
     *   gross = quantity × unit price
     *   taxable = gross − discount (+ apportioned shipping, if configured taxable)
     *   tax = taxable × rate
     *
     * The rate comes from the tax master as it stood on the invoice date, so a
     * later rate change cannot alter this invoice.
     */
    private Totals buildLines(SalesInvoiceEntity invoice, CustomerOrderEntity order,
                              List<CustomerOrderItemEntity> orderItems, LocalDate invoiceDate,
                              String placeOfSupply, String sellerState) {
        Totals t = new Totals();

        long shipping = nvl(order.getShippingValue());
        boolean shippingTaxable = configService.shippingIsTaxable(orgId());
        long shippingPool = shippingTaxable ? shipping : 0L;

        // Apportion shipping across lines by taxable value so each line is taxed
        // at its own rate, rather than guessing a single rate for the whole
        // shipping charge.
        long baseForApportion = orderItems.stream()
                .mapToLong(i -> Math.max(0, nvl(i.getGrossValue()) - nvl(i.getDiscountPaise())))
                .sum();

        List<SalesInvoiceItemEntity> lines = new ArrayList<>();
        int lineNo = 1;
        long shippingAllocated = 0;

        for (int idx = 0; idx < orderItems.size(); idx++) {
            CustomerOrderItemEntity oi = orderItems.get(idx);
            int qty = oi.getQuantity() != null ? oi.getQuantity() : 1;
            long unitPrice = nvl(oi.getUnitPricePaise());
            long gross = nvl(oi.getGrossValue()) > 0 ? nvl(oi.getGrossValue()) : unitPrice * qty;
            long discount = Math.min(nvl(oi.getDiscountPaise()), gross);
            long lineBase = gross - discount;

            // Last line absorbs the apportionment remainder so the parts sum exactly.
            long shippingShare = 0;
            if (shippingPool > 0 && baseForApportion > 0) {
                shippingShare = (idx == orderItems.size() - 1)
                        ? shippingPool - shippingAllocated
                        : rounding.proportionalShare(shippingPool, (int) Math.min(Integer.MAX_VALUE, lineBase),
                                                     (int) Math.min(Integer.MAX_VALUE, baseForApportion));
                shippingAllocated += shippingShare;
            }

            long taxable = lineBase + shippingShare;

            GstCalculationService.GstResult gst = calculationService.calculateOnTaxableValue(
                    oi.getHsnCode(), taxable, qty, placeOfSupply, sellerState, invoiceDate);

            String productName = productRepo.findByUuid(oi.getProductUuid())
                    .map(InventoryProductEntity::getName).orElse(null);

            SalesInvoiceItemEntity line = itemRepo.save(SalesInvoiceItemEntity.builder()
                    .salesInvoiceId(invoice.getId())
                    .orderItemId(oi.getId())
                    .lineNumber(lineNo++)
                    .productUuid(oi.getProductUuid())
                    .productName(productName)
                    .description(oi.getSize() != null ? "Size " + oi.getSize() : null)
                    .hsnCode(oi.getHsnCode())
                    .quantity(qty)
                    .unit("PCS")
                    .unitPrice(unitPrice)
                    .grossValue(gross)
                    .discount(discount)
                    .taxableValue(taxable)
                    .gstRateBp(gst.cgstRateBp() + gst.sgstRateBp() + gst.igstRateBp())
                    .cgstRateBp(gst.cgstRateBp()).cgstAmount(gst.cgstAmount())
                    .sgstRateBp(gst.sgstRateBp()).sgstAmount(gst.sgstAmount())
                    .igstRateBp(gst.igstRateBp()).igstAmount(gst.igstAmount())
                    .cessRateBp(gst.cessRateBp()).cessAmount(gst.cessAmount())
                    .totalTax(gst.totalTax())
                    .totalValue(taxable + gst.totalTax())
                    .build());
            lines.add(line);

            t.gross += gross;
            t.discount += discount;
            t.taxable += taxable;
            t.cgst += gst.cgstAmount();
            t.sgst += gst.sgstAmount();
            t.igst += gst.igstAmount();
            t.cess += gst.cessAmount();
        }
        t.shippingTaxable = shippingAllocated;
        return t;
    }

    private void applyTotals(SalesInvoiceEntity invoice, CustomerOrderEntity order, Totals t) {
        long shipping = nvl(order.getShippingValue());
        long subtotal = t.taxable + t.totalTax() + (shipping - t.shippingTaxable);

        // Round the invoice total to the nearest rupee and record the difference.
        long rounded = Math.round(subtotal / 100.0) * 100;
        long roundOff = rounded - subtotal;

        invoice.setGrossValue(t.gross);
        invoice.setDiscountValue(t.discount);
        invoice.setShippingValue(shipping);
        invoice.setShippingTaxableValue(t.shippingTaxable);
        invoice.setTaxableValue(t.taxable);
        invoice.setCgstAmount(t.cgst);
        invoice.setSgstAmount(t.sgst);
        invoice.setIgstAmount(t.igst);
        invoice.setCessAmount(t.cess);
        invoice.setTotalTax(t.totalTax());
        invoice.setRoundOff(roundOff);
        invoice.setTotalInvoiceValue(rounded);
    }

    // ── Issue ────────────────────────────────────────────────────────────────

    /**
     * Issues a draft: allocates the invoice number and posts the ledger
     * movements. After this the invoice is immutable.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public SalesInvoiceEntity issue(Long invoiceId) throws VeloriaException {
        SalesInvoiceEntity inv = load(invoiceId);
        if (!DRAFT.equals(inv.getStatus())) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "Invoice " + invoiceId + " is " + inv.getStatus() + " and cannot be issued again");
        }
        periodService.assertOpen(inv.getTaxPeriod());

        String number = numberService.next(inv.getOrganizationId(), inv.getGstRegistrationId(),
                GstInvoiceNumberService.SALES_INVOICE, inv.getInvoiceDate());

        inv.setInvoiceNumber(number);
        inv.setStatus(ISSUED);
        inv.setIssuedAt(Instant.now());
        inv.setIssuedBy(securityContext.actor());
        invoiceRepo.save(inv);

        // The invoice is now the GST document for this supply, so the movements
        // posted at order placement are superseded. Without this the same supply
        // is counted twice in output tax — once from the order, once from the
        // invoice. Superseded rows are retained for audit, never deleted.
        supersedeOrderMovements(inv);

        postMovements(inv, +1, GstMovementService.SALE_OUTPUT, "Invoice " + number);

        auditService.log("SALES_INVOICE", inv.getId(), number, "INVOICE_ISSUED",
                "status", DRAFT, ISSUED, inv.getTaxPeriod(), securityContext.actor());
        log.info("Invoice {} issued for order {} — taxable {} paise, tax {} paise",
                number, inv.getOrderCode(), inv.getTaxableValue(), inv.getTotalTax());
        return inv;
    }

    // ── Cancel (spec phase 9) ────────────────────────────────────────────────

    /**
     * Cancels an issued invoice.
     *
     * The invoice row and its number are preserved. GST is reversed by posting
     * offsetting ledger movements, not by deleting the originals.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public SalesInvoiceEntity cancel(Long invoiceId, String reason) throws VeloriaException {
        if (reason == null || reason.isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A reason is required to cancel an invoice");
        }
        SalesInvoiceEntity inv = load(invoiceId);

        if (CANCELLED.equals(inv.getStatus())) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "Invoice " + inv.getInvoiceNumber() + " is already cancelled");
        }
        if (AMENDED.equals(inv.getStatus())) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "Invoice " + inv.getInvoiceNumber() + " has been amended; cancel the amending invoice instead");
        }
        // Once the period is closed, cancellation is no longer the right
        // mechanism — a credit note is (spec phase 9).
        if (periodService.isLocked(inv.getTaxPeriod())) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "Period " + inv.getTaxPeriod() + " is closed. Raise a credit note against invoice "
                            + inv.getInvoiceNumber() + " instead of cancelling it.");
        }

        String old = inv.getStatus();
        inv.setStatus(CANCELLED);
        inv.setCancelledAt(Instant.now());
        inv.setCancelledBy(securityContext.actor());
        inv.setCancellationReason(reason);
        invoiceRepo.save(inv);

        if (Boolean.TRUE.equals(inv.getMovementPosted())) {
            postMovements(inv, -1, GstMovementService.OTHER_ADJUSTMENT,
                    "Cancellation of invoice " + inv.getInvoiceNumber() + " — " + reason);
        }

        auditService.log("SALES_INVOICE", inv.getId(), inv.getInvoiceNumber(), "INVOICE_CANCELLED",
                "status", old, CANCELLED, inv.getTaxPeriod(), securityContext.actor());
        log.info("Invoice {} cancelled by {} — {}", inv.getInvoiceNumber(), securityContext.actor(), reason);
        return inv;
    }

    // ── Amend (spec phase 10) ────────────────────────────────────────────────

    /**
     * Amends an issued invoice by rebuilding it from the current order state as
     * a new invoice that references the original.
     *
     * The original is never modified; it is marked AMENDED and its GST is
     * reversed, and the replacement carries its own number and movements.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public SalesInvoiceEntity amend(Long invoiceId, String reason) throws VeloriaException {
        if (reason == null || reason.isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A reason is required to amend an invoice");
        }
        SalesInvoiceEntity original = load(invoiceId);
        if (!ISSUED.equals(original.getStatus())) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "Only an ISSUED invoice can be amended; invoice " + invoiceId
                            + " is " + original.getStatus());
        }
        periodService.assertOpen(YearMonth.now().toString());

        CustomerOrderEntity order = orderRepo.findById(original.getOrderId())
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Order behind invoice " + invoiceId + " no longer exists"));
        List<CustomerOrderItemEntity> orderItems =
                orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(order.getId());

        // Reverse the original, then mark it amended. The row stays untouched
        // apart from its status and the amendment metadata.
        if (Boolean.TRUE.equals(original.getMovementPosted())) {
            postMovements(original, -1, GstMovementService.OTHER_ADJUSTMENT,
                    "Amendment of invoice " + original.getInvoiceNumber() + " — " + reason);
        }
        original.setStatus(AMENDED);
        original.setAmendedAt(Instant.now());
        original.setAmendedBy(securityContext.actor());
        original.setAmendmentReason(reason);
        invoiceRepo.save(original);

        LocalDate today = LocalDate.now();
        String taxPeriod = YearMonth.from(today).toString();
        String sellerState = identityService.sellerStateCode();
        String placeOfSupply = resolvePlaceOfSupply(order);

        SalesInvoiceEntity amended = invoiceRepo.save(SalesInvoiceEntity.builder()
                .invoiceDate(today)
                .organizationId(original.getOrganizationId())
                .gstRegistrationId(original.getGstRegistrationId())
                .orderId(order.getId())
                .orderCode(order.getOrderCode())
                .customerId(order.getCustomerId())
                .customerType(order.getCustomerType())
                .customerGstin(order.getCustomerGstin())
                .customerName(order.getCustomerName())
                .customerLegalName(order.getCustomerLegalName())
                .billingAddressSnapshot(order.getDeliveryLocation())
                .shippingAddressSnapshot(order.getDeliveryLocation())
                .placeOfSupply(placeOfSupply)
                .sellerGstin(identityService.businessGstin())
                .sellerStateCode(sellerState)
                .supplyType(safeSupplyType(placeOfSupply, sellerState, order))
                .currency(original.getCurrency())
                .status(DRAFT)
                .taxPeriod(taxPeriod)
                .financialYear(GstInvoiceNumberService.financialYear(today))
                .originalInvoiceId(original.getId())
                .createdBy(securityContext.actor())
                .build());

        Totals t = buildLines(amended, order, orderItems, today, placeOfSupply, sellerState);
        applyTotals(amended, order, t);
        invoiceRepo.save(amended);

        SalesInvoiceEntity issued = issue(amended.getId());

        auditService.log("SALES_INVOICE", original.getId(), original.getInvoiceNumber(),
                "INVOICE_AMENDED", "amendedBy", original.getInvoiceNumber(),
                issued.getInvoiceNumber(), taxPeriod, securityContext.actor());
        log.info("Invoice {} amended by {} — replacement {}",
                original.getInvoiceNumber(), securityContext.actor(), issued.getInvoiceNumber());
        return issued;
    }

    // ── Ledger posting ───────────────────────────────────────────────────────

    /**
     * Posts one ledger movement per invoice line. sign = +1 for an issue,
     * -1 for a cancellation or amendment reversal.
     */
    private void postMovements(SalesInvoiceEntity inv, int sign, String movementType, String reason) {
        List<SalesInvoiceItemEntity> lines = itemRepo.findBySalesInvoiceIdOrderByLineNumberAsc(inv.getId());
        String period = sign > 0 ? inv.getTaxPeriod() : YearMonth.now().toString();

        List<GstMovementLedgerEntity> toSave = new ArrayList<>();
        for (SalesInvoiceItemEntity line : lines) {
            String sourceType = sign > 0 ? "SALES_INVOICE_ITEM" : "SALES_INVOICE_ITEM_REVERSAL";
            if (movementRepo.existsBySourceTypeAndSourceId(sourceType, line.getId())) continue;

            toSave.add(GstMovementLedgerEntity.builder()
                    .movementNumber(inv.getInvoiceNumber() + "-L" + line.getLineNumber()
                            + (sign < 0 ? "-REV" : ""))
                    .movementType(movementType)
                    .direction(GstMovementService.DIR_OUT)
                    .sourceType(sourceType)
                    .sourceId(line.getId())
                    .sourceDocumentNumber(inv.getInvoiceNumber())
                    .originalDocumentId(inv.getOrderId())
                    .originalDocumentNumber(inv.getOrderCode())
                    .transactionDate(sign > 0 ? inv.getInvoiceDate() : LocalDate.now())
                    .taxPeriod(period)
                    .financialYear(inv.getFinancialYear())
                    .businessGstin(inv.getSellerGstin())
                    .counterpartyName(inv.getCustomerName())
                    .counterpartyGstin(inv.getCustomerGstin())
                    .counterpartyType("CUSTOMER")
                    .orderItemId(line.getOrderItemId())
                    .productUuid(line.getProductUuid() != null ? line.getProductUuid().toString() : null)
                    .productName(line.getProductName())
                    .hsnCode(line.getHsnCode())
                    .quantity(line.getQuantity())
                    .unitPricePaise(line.getUnitPrice())
                    .taxableValuePaise(sign * nvl(line.getTaxableValue()))
                    .cgstRateBp(line.getCgstRateBp())
                    .sgstRateBp(line.getSgstRateBp())
                    .igstRateBp(line.getIgstRateBp())
                    .cgstAmountPaise(sign * nvl(line.getCgstAmount()))
                    .sgstAmountPaise(sign * nvl(line.getSgstAmount()))
                    .igstAmountPaise(sign * nvl(line.getIgstAmount()))
                    .totalTaxPaise(sign * nvl(line.getTotalTax()))
                    .placeOfSupply(inv.getPlaceOfSupply())
                    .sellerStateCode(inv.getSellerStateCode())
                    .buyerStateCode(inv.getPlaceOfSupply())
                    .supplyType(inv.getSupplyType())
                    .status("POSTED")
                    .reason(reason)
                    .createdBy(securityContext.actor())
                    .organizationId(inv.getOrganizationId())
                    .gstRegistrationId(inv.getGstRegistrationId())
                    .build());
        }
        if (toSave.isEmpty()) return;
        movementRepo.saveAll(toSave);

        if (sign > 0) {
            inv.setMovementPosted(true);
            invoiceRepo.save(inv);
        }
    }

    /**
     * Retires the order-level SALE_OUTPUT movements once an invoice covers the
     * same supply.
     *
     * Order placement posts provisional movements so GST is visible before an
     * invoice exists. Once the invoice is issued it becomes the accounting
     * document, and keeping both would double-count output tax.
     */
    private void supersedeOrderMovements(SalesInvoiceEntity inv) {
        if (inv.getOrderId() == null) return;
        List<GstMovementLedgerEntity> orderMovements =
                movementRepo.findBySourceTypeAndStatus("CUSTOMER_ORDER_ITEM", "POSTED").stream()
                        .filter(m -> inv.getOrderCode() != null
                                && inv.getOrderCode().equals(m.getSourceDocumentNumber()))
                        .toList();
        if (orderMovements.isEmpty()) return;

        for (GstMovementLedgerEntity m : orderMovements) {
            m.setStatus("SUPERSEDED");
            m.setReason("Superseded by invoice " + inv.getInvoiceNumber());
        }
        movementRepo.saveAll(orderMovements);
        log.info("Invoice {} superseded {} order-level movement(s) for order {}",
                inv.getInvoiceNumber(), orderMovements.size(), inv.getOrderCode());
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    public List<SalesInvoiceItemEntity> itemsOf(Long invoiceId) {
        return itemRepo.findBySalesInvoiceIdOrderByLineNumberAsc(invoiceId);
    }

    public Optional<SalesInvoiceEntity> byNumber(String number) {
        return invoiceRepo.findByInvoiceNumber(number);
    }

    private SalesInvoiceEntity load(Long id) throws VeloriaException {
        SalesInvoiceEntity inv = invoiceRepo.findById(id)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Invoice not found: " + id));
        Long org = orgId();
        if (inv.getOrganizationId() != null && org != null && !inv.getOrganizationId().equals(org)) {
            throw new VeloriaException(ResponseCode.NOT_FOUND, "Invoice not found: " + id);
        }
        return inv;
    }

    private String resolvePlaceOfSupply(CustomerOrderEntity order) {
        // A B2B customer's GSTIN is authoritative over any stored state code.
        if ("B2B".equals(order.getCustomerType())
                && identityService.isValidGstin(order.getCustomerGstin())) {
            return identityService.stateCodeOf(order.getCustomerGstin());
        }
        return order.getPlaceOfSupply() != null ? order.getPlaceOfSupply() : order.getBuyerStateCode();
    }

    private String safeSupplyType(String placeOfSupply, String sellerState, CustomerOrderEntity order) {
        try {
            return identityService.supplyType(placeOfSupply, sellerState);
        } catch (IllegalStateException unresolved) {
            return nvl(order.getIgstAmount()) > 0 ? "INTER_STATE" : "INTRA_STATE";
        }
    }

    private static long nvl(Long v) { return v != null ? v : 0L; }
}
