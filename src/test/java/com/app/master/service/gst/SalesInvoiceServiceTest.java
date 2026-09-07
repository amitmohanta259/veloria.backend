package com.app.master.service.gst;

import com.app.master.service.core.entity.*;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.security.GstSecurityContext;
import com.app.master.service.repository.admin.*;
import com.app.master.service.service.admin.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The sales invoice layer (spec phases 6-13).
 *
 * The invoice is the GST document, separate from the order. These tests cover
 * the calculation order (gross → discount → taxable → tax), the lifecycle, and
 * the immutability guarantees.
 */
class SalesInvoiceServiceTest {

    private static final String ODISHA = "21";
    private static final String ORDER_CODE = "ORD-INV-1";

    private SalesInvoiceRepository invoiceRepo;
    private SalesInvoiceItemRepository itemRepo;
    private CustomerOrderRepository orderRepo;
    private CustomerOrderItemRepository orderItemRepo;
    private GstMovementLedgerRepository movementRepo;
    private GstInvoiceNumberService numberService;
    private GstCalculationService calcService;
    private GstConfigurationService configService;
    private GstTaxPeriodService periodService;
    private GstIdentityService identityService;
    private SalesInvoiceService service;

    private CustomerOrderEntity order;
    private final List<SalesInvoiceItemEntity> savedItems = new ArrayList<>();
    private final List<GstMovementLedgerEntity> savedMovements = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        invoiceRepo = mock(SalesInvoiceRepository.class);
        itemRepo = mock(SalesInvoiceItemRepository.class);
        orderRepo = mock(CustomerOrderRepository.class);
        orderItemRepo = mock(CustomerOrderItemRepository.class);
        movementRepo = mock(GstMovementLedgerRepository.class);
        numberService = mock(GstInvoiceNumberService.class);
        calcService = mock(GstCalculationService.class);
        configService = mock(GstConfigurationService.class);
        periodService = mock(GstTaxPeriodService.class);
        identityService = mock(GstIdentityService.class);
        InventoryProductRepository productRepo = mock(InventoryProductRepository.class);
        GstAuditService audit = mock(GstAuditService.class);
        GstSecurityContext ctx = mock(GstSecurityContext.class);

        when(ctx.current()).thenReturn(Optional.empty());
        when(ctx.actor()).thenReturn("tester");
        when(identityService.defaultOrganizationId()).thenReturn(1L);
        when(identityService.primaryRegistration(any())).thenReturn(Optional.empty());
        when(identityService.sellerStateCode()).thenReturn(ODISHA);
        when(identityService.businessGstin()).thenReturn("21AABCU9603R1ZX");
        when(identityService.supplyType(anyString(), anyString())).thenAnswer(i ->
                i.getArgument(0).equals(i.getArgument(1)) ? "INTRA_STATE" : "INTER_STATE");
        when(identityService.isValidGstin(anyString())).thenAnswer(i -> {
            String g = i.getArgument(0);
            return g != null && g.matches("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$");
        });
        when(identityService.stateCodeOf(anyString())).thenAnswer(i -> {
            String g = i.getArgument(0);
            return g != null && g.length() == 15 ? g.substring(0, 2) : null;
        });
        doNothing().when(periodService).assertOpen(anyString());
        when(periodService.isLocked(anyString())).thenReturn(false);
        when(configService.shippingIsTaxable(any())).thenReturn(true);
        when(productRepo.findByUuid(any())).thenReturn(Optional.empty());
        when(numberService.next(any(), any(), anyString(), any())).thenReturn("FY26-27/INV/000001");
        when(movementRepo.existsBySourceTypeAndSourceId(anyString(), anyLong())).thenReturn(false);

        order = CustomerOrderEntity.builder()
                .id(1L).orderCode(ORDER_CODE).customerId("c1").customerName("Test Customer")
                .customerType("B2C").currency("INR")
                .orderPlacedAt(Instant.parse("2026-08-15T10:00:00Z"))
                .placeOfSupply(ODISHA).buyerStateCode(ODISHA).sellerStateCode(ODISHA)
                .shippingValue(0L)
                .build();

        when(orderRepo.findByOrderCodeAndArchiveFalse(ORDER_CODE)).thenReturn(Optional.of(order));
        when(orderRepo.findById(1L)).thenReturn(Optional.of(order));
        when(invoiceRepo.findActiveForOrder(1L)).thenReturn(List.of());

        AtomicLong invIds = new AtomicLong(10);
        java.util.Map<Long, SalesInvoiceEntity> store = new java.util.HashMap<>();
        when(invoiceRepo.save(any())).thenAnswer(i -> {
            SalesInvoiceEntity inv = i.getArgument(0);
            if (inv.getId() == null) inv.setId(invIds.incrementAndGet());
            store.put(inv.getId(), inv);
            return inv;
        });
        when(invoiceRepo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(store.get(i.getArgument(0))));
        AtomicLong itemIds = new AtomicLong(100);
        savedItems.clear();
        when(itemRepo.save(any())).thenAnswer(i -> {
            SalesInvoiceItemEntity it = i.getArgument(0);
            if (it.getId() == null) it.setId(itemIds.incrementAndGet());
            savedItems.add(it);
            return it;
        });
        when(itemRepo.findBySalesInvoiceIdOrderByLineNumberAsc(anyLong()))
                .thenAnswer(i -> new ArrayList<>(savedItems));
        savedMovements.clear();
        when(movementRepo.saveAll(any())).thenAnswer(i -> {
            List<GstMovementLedgerEntity> ms = i.getArgument(0);
            savedMovements.addAll(ms);
            return ms;
        });

        service = new SalesInvoiceService(invoiceRepo, itemRepo, orderRepo, orderItemRepo,
                productRepo, movementRepo, numberService, calcService, new GstRoundingService(),
                identityService, configService, periodService, audit, ctx);
    }

    private CustomerOrderItemEntity item(long id, long unitPrice, int qty, long discount) {
        return CustomerOrderItemEntity.builder()
                .id(id).customerOrderId(1L).productUuid(UUID.randomUUID())
                .unitPricePaise(unitPrice).quantity(qty)
                .grossValue(unitPrice * qty).discountPaise(discount)
                .taxableValuePaise(unitPrice * qty - discount)
                .hsnCode("6204")
                .build();
    }

    /** Stubs the calculator to apply a flat intra-state rate to whatever base it is given. */
    private void flatRate(int cgstBp, int sgstBp, int cessBp) {
        when(calcService.calculateOnTaxableValue(anyString(), anyLong(), anyInt(), anyString(), anyString(), any()))
                .thenAnswer(i -> {
                    long taxable = i.getArgument(1);
                    GstRoundingService r = new GstRoundingService();
                    long c = r.taxOn(taxable, cgstBp), s = r.taxOn(taxable, sgstBp), ce = r.taxOn(taxable, cessBp);
                    return new GstCalculationService.GstResult("6204", taxable, taxable,
                            i.getArgument(2), cgstBp, sgstBp, 0, cessBp, c, s, 0L, ce,
                            c + s + ce, false, "RULE_APPLIED");
                });
    }

    // ── Calculation order (spec phase 11) ────────────────────────────────────

    @Test
    @DisplayName("Taxable value is gross minus discount, and tax is charged on that")
    void discountReducesTheTaxBase() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 2, 20000L)));  // 2000.00 gross, 200.00 off
        flatRate(250, 250, 0);

        SalesInvoiceEntity inv = service.createDraft(ORDER_CODE);

        assertEquals(200000L, inv.getGrossValue());
        assertEquals(20000L, inv.getDiscountValue());
        assertEquals(180000L, inv.getTaxableValue(), "Tax base is gross minus discount");
        assertEquals(4500L, inv.getCgstAmount(), "2.5% of 1800.00");
        assertEquals(4500L, inv.getSgstAmount());
    }

    @Test
    @DisplayName("A discount larger than the line is capped, never producing negative tax")
    void discountCannotExceedGross() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 500000L)));  // absurd discount
        flatRate(250, 250, 0);

        SalesInvoiceEntity inv = service.createDraft(ORDER_CODE);

        assertEquals(0L, inv.getTaxableValue(), "Taxable value floors at zero");
        assertTrue(inv.getTaxableValue() >= 0);
        assertEquals(0L, inv.getCgstAmount());
    }

    @Test
    @DisplayName("Shipping is apportioned across lines and taxed at each line's rate")
    void shippingIsApportioned() throws Exception {
        order.setShippingValue(10000L);  // 100.00 shipping
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L), item(2L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);

        SalesInvoiceEntity inv = service.createDraft(ORDER_CODE);

        assertEquals(10000L, inv.getShippingTaxableValue(), "All shipping entered the tax base");
        assertEquals(210000L, inv.getTaxableValue(), "2000.00 goods + 100.00 shipping");
        long lineSum = savedItems.stream().mapToLong(SalesInvoiceItemEntity::getTaxableValue).sum();
        assertEquals(inv.getTaxableValue(), lineSum, "Apportioned parts sum to the whole");
    }

    @Test
    @DisplayName("Shipping configured EXEMPT stays out of the tax base")
    void exemptShippingIsNotTaxed() throws Exception {
        when(configService.shippingIsTaxable(any())).thenReturn(false);
        order.setShippingValue(10000L);
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);

        SalesInvoiceEntity inv = service.createDraft(ORDER_CODE);

        assertEquals(0L, inv.getShippingTaxableValue());
        assertEquals(100000L, inv.getTaxableValue(), "Only the goods are taxed");
        assertEquals(10000L, inv.getShippingValue(), "Shipping is still recorded");
    }

    @Test
    @DisplayName("Cess is carried onto the invoice when the rule sets a rate")
    void cessIsApplied() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 100);   // 1% cess

        SalesInvoiceEntity inv = service.createDraft(ORDER_CODE);

        assertEquals(1000L, inv.getCessAmount(), "1% of 1000.00");
        assertEquals(2500L + 2500L + 1000L, inv.getTotalTax(), "Cess is part of total tax");
    }

    @Test
    @DisplayName("No cess when the rule sets no rate")
    void noCessWhenRuleHasNone() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);

        assertEquals(0L, service.createDraft(ORDER_CODE).getCessAmount());
    }

    @Test
    @DisplayName("The invoice total is rounded and the difference recorded")
    void roundOffIsRecorded() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 33333L, 1, 0L)));
        flatRate(250, 250, 0);

        SalesInvoiceEntity inv = service.createDraft(ORDER_CODE);

        long subtotal = inv.getTaxableValue() + inv.getTotalTax();
        assertEquals(inv.getTotalInvoiceValue(), subtotal + inv.getRoundOff(),
                "total = subtotal + roundOff");
        assertEquals(0, inv.getTotalInvoiceValue() % 100, "Total is a whole rupee");
    }

    // ── B2B (spec phase 5) ───────────────────────────────────────────────────

    @Test
    @DisplayName("A B2B order without a GSTIN cannot be invoiced")
    void b2bWithoutGstinIsRefused() {
        order.setCustomerType("B2B");
        order.setCustomerGstin(null);

        VeloriaException ex = assertThrows(VeloriaException.class, () -> service.createDraft(ORDER_CODE));
        assertTrue(ex.getMessage().contains("no customer GSTIN"), ex.getMessage());
    }

    @Test
    @DisplayName("An invalid B2B GSTIN is refused, not silently downgraded to B2C")
    void invalidB2bGstinIsRefused() {
        order.setCustomerType("B2B");
        order.setCustomerGstin("NOT-A-GSTIN");

        VeloriaException ex = assertThrows(VeloriaException.class, () -> service.createDraft(ORDER_CODE));
        assertTrue(ex.getMessage().contains("not valid"), ex.getMessage());
        assertEquals("B2B", order.getCustomerType(), "The order must not be quietly changed to B2C");
    }

    @Test
    @DisplayName("A B2B customer's GSTIN determines the place of supply")
    void b2bGstinDrivesPlaceOfSupply() throws Exception {
        order.setCustomerType("B2B");
        order.setCustomerGstin("27AAPFU0939F1ZV");   // Maharashtra
        order.setPlaceOfSupply(ODISHA);               // stale, must be overridden
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);

        SalesInvoiceEntity inv = service.createDraft(ORDER_CODE);

        assertEquals("27", inv.getPlaceOfSupply(), "GSTIN wins over the stored state code");
        assertEquals("INTER_STATE", inv.getSupplyType());
    }

    // ── Lifecycle (spec phases 8-10) ─────────────────────────────────────────

    @Test
    @DisplayName("A draft has no invoice number until it is issued")
    void draftHasNoNumber() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);

        SalesInvoiceEntity inv = service.createDraft(ORDER_CODE);

        assertEquals("DRAFT", inv.getStatus());
        assertNull(inv.getInvoiceNumber());
    }

    @Test
    @DisplayName("Issuing allocates a number and posts one ledger movement per line")
    void issueAllocatesNumberAndPosts() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L), item(2L, 50000L, 1, 0L)));
        flatRate(250, 250, 0);
        SalesInvoiceEntity draft = service.createDraft(ORDER_CODE);
        when(invoiceRepo.findById(draft.getId())).thenReturn(Optional.of(draft));

        SalesInvoiceEntity issued = service.issue(draft.getId());

        assertEquals("ISSUED", issued.getStatus());
        assertEquals("FY26-27/INV/000001", issued.getInvoiceNumber());
        assertNotNull(issued.getIssuedAt());
        assertEquals("tester", issued.getIssuedBy());
        assertEquals(2, savedMovements.size(), "One movement per invoice line");
    }

    @Test
    @DisplayName("Issuing supersedes the order's own movements, so output tax is not counted twice")
    void issueSupersedesOrderLevelMovements() throws Exception {
        GstMovementLedgerEntity orderMovement = GstMovementLedgerEntity.builder()
                .id(900L).movementNumber("GST-MOV-1").status("POSTED")
                .direction(GstMovementService.DIR_OUT)
                .sourceType("CUSTOMER_ORDER_ITEM").sourceDocumentNumber(ORDER_CODE)
                .totalTaxPaise(5000L)
                .build();
        when(movementRepo.findBySourceTypeAndStatus("CUSTOMER_ORDER_ITEM", "POSTED"))
                .thenReturn(List.of(orderMovement));
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);
        SalesInvoiceEntity draft = service.createDraft(ORDER_CODE);
        when(invoiceRepo.findById(draft.getId())).thenReturn(Optional.of(draft));

        service.issue(draft.getId());

        assertEquals("SUPERSEDED", orderMovement.getStatus(),
                "the invoice is the GST document once issued, so the order movement must retire");
        assertTrue(orderMovement.getReason().contains("FY26-27/INV/000001"),
                "the superseding invoice must be named for audit");
    }

    @Test
    @DisplayName("Issuing leaves another order's movements untouched")
    void issueDoesNotSupersedeUnrelatedOrders() throws Exception {
        GstMovementLedgerEntity otherOrder = GstMovementLedgerEntity.builder()
                .id(901L).movementNumber("GST-MOV-2").status("POSTED")
                .direction(GstMovementService.DIR_OUT)
                .sourceType("CUSTOMER_ORDER_ITEM").sourceDocumentNumber("ORD-SOMEONE-ELSE")
                .totalTaxPaise(7000L)
                .build();
        when(movementRepo.findBySourceTypeAndStatus("CUSTOMER_ORDER_ITEM", "POSTED"))
                .thenReturn(List.of(otherOrder));
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);
        SalesInvoiceEntity draft = service.createDraft(ORDER_CODE);
        when(invoiceRepo.findById(draft.getId())).thenReturn(Optional.of(draft));

        service.issue(draft.getId());

        assertEquals("POSTED", otherOrder.getStatus());
    }

    @Test
    @DisplayName("An issued invoice cannot be issued again")
    void cannotIssueTwice() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);
        SalesInvoiceEntity draft = service.createDraft(ORDER_CODE);
        when(invoiceRepo.findById(draft.getId())).thenReturn(Optional.of(draft));
        service.issue(draft.getId());

        assertThrows(VeloriaException.class, () -> service.issue(draft.getId()));
    }

    @Test
    @DisplayName("Cancellation requires a reason")
    void cancelRequiresReason() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);
        SalesInvoiceEntity draft = service.createDraft(ORDER_CODE);
        when(invoiceRepo.findById(draft.getId())).thenReturn(Optional.of(draft));
        service.issue(draft.getId());

        assertThrows(VeloriaException.class, () -> service.cancel(draft.getId(), null));
        assertThrows(VeloriaException.class, () -> service.cancel(draft.getId(), "  "));
    }

    @Test
    @DisplayName("Cancelling preserves the invoice and its number, and reverses the ledger")
    void cancelPreservesAndReverses() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);
        SalesInvoiceEntity draft = service.createDraft(ORDER_CODE);
        when(invoiceRepo.findById(draft.getId())).thenReturn(Optional.of(draft));
        service.issue(draft.getId());
        int afterIssue = savedMovements.size();

        SalesInvoiceEntity cancelled = service.cancel(draft.getId(), "Customer cancelled");

        assertEquals("CANCELLED", cancelled.getStatus());
        assertEquals("FY26-27/INV/000001", cancelled.getInvoiceNumber(), "Number is retained");
        assertEquals("Customer cancelled", cancelled.getCancellationReason());
        assertNotNull(cancelled.getCancelledAt());
        assertTrue(savedMovements.size() > afterIssue, "A reversal was posted");
        assertTrue(savedMovements.get(savedMovements.size() - 1).getTotalTaxPaise() < 0,
                "The reversal is negative");
    }

    @Test
    @DisplayName("An invoice cannot be cancelled twice")
    void cannotCancelTwice() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);
        SalesInvoiceEntity draft = service.createDraft(ORDER_CODE);
        when(invoiceRepo.findById(draft.getId())).thenReturn(Optional.of(draft));
        service.issue(draft.getId());
        service.cancel(draft.getId(), "First");

        VeloriaException ex = assertThrows(VeloriaException.class,
                () -> service.cancel(draft.getId(), "Second"));
        assertTrue(ex.getMessage().contains("already cancelled"), ex.getMessage());
    }

    @Test
    @DisplayName("A closed period blocks cancellation and points at the credit note instead")
    void lockedPeriodBlocksCancellation() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);
        SalesInvoiceEntity draft = service.createDraft(ORDER_CODE);
        when(invoiceRepo.findById(draft.getId())).thenReturn(Optional.of(draft));
        service.issue(draft.getId());
        when(periodService.isLocked(anyString())).thenReturn(true);

        VeloriaException ex = assertThrows(VeloriaException.class,
                () -> service.cancel(draft.getId(), "Too late"));
        assertTrue(ex.getMessage().contains("credit note"),
                "The error should name the correct remedy: " + ex.getMessage());
    }

    @Test
    @DisplayName("Amending leaves the original intact and links the replacement to it")
    void amendPreservesOriginal() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);
        SalesInvoiceEntity original = service.createDraft(ORDER_CODE);
        when(invoiceRepo.findById(original.getId())).thenReturn(Optional.of(original));
        service.issue(original.getId());
        String originalNumber = original.getInvoiceNumber();
        long originalTaxable = original.getTaxableValue();

        when(numberService.next(any(), any(), anyString(), any())).thenReturn("FY26-27/INV/000002");
        SalesInvoiceEntity amended = service.amend(original.getId(), "Corrected address");

        assertEquals("AMENDED", original.getStatus(), "Original is marked, not deleted");
        assertEquals(originalNumber, original.getInvoiceNumber(), "Original keeps its number");
        assertEquals(originalTaxable, original.getTaxableValue(), "Original amounts are unchanged");
        assertEquals("Corrected address", original.getAmendmentReason());
        assertEquals(original.getId(), amended.getOriginalInvoiceId(), "Replacement references the original");
        assertNotEquals(originalNumber, amended.getInvoiceNumber(), "Replacement has its own number");
    }

    @Test
    @DisplayName("Only an issued invoice can be amended")
    void onlyIssuedCanBeAmended() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);
        SalesInvoiceEntity draft = service.createDraft(ORDER_CODE);
        when(invoiceRepo.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThrows(VeloriaException.class, () -> service.amend(draft.getId(), "too early"));
    }

    // ── Idempotency and immutability ─────────────────────────────────────────

    @Test
    @DisplayName("An order that already has a live invoice does not get a second one")
    void draftIsIdempotentPerOrder() throws Exception {
        SalesInvoiceEntity existing = SalesInvoiceEntity.builder()
                .id(99L).orderId(1L).invoiceNumber("FY26-27/INV/000009").status("ISSUED").build();
        when(invoiceRepo.findActiveForOrder(1L)).thenReturn(List.of(existing));

        assertEquals(99L, service.createDraft(ORDER_CODE).getId());
        verify(itemRepo, never()).save(any());
    }

    @Test
    @DisplayName("An order with no items cannot be invoiced")
    void emptyOrderCannotBeInvoiced() {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L)).thenReturn(List.of());
        assertThrows(VeloriaException.class, () -> service.createDraft(ORDER_CODE));
    }

    @Test
    @DisplayName("An unknown order is rejected")
    void unknownOrderRejected() {
        when(orderRepo.findByOrderCodeAndArchiveFalse("NOPE")).thenReturn(Optional.empty());
        assertThrows(VeloriaException.class, () -> service.createDraft("NOPE"));
    }

    @Test
    @DisplayName("Invoice lines carry the rate snapshot, so a later rate change cannot alter them")
    void linesCarryRateSnapshot() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);

        service.createDraft(ORDER_CODE);

        SalesInvoiceItemEntity line = savedItems.get(0);
        assertEquals(250, line.getCgstRateBp());
        assertEquals(250, line.getSgstRateBp());
        assertEquals("6204", line.getHsnCode());
        assertEquals(500, line.getGstRateBp(), "Combined rate is stored for display");
    }

    @Test
    @DisplayName("The rate is looked up on the invoice date, not today")
    void rateLookupUsesInvoiceDate() throws Exception {
        when(orderItemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(item(1L, 100000L, 1, 0L)));
        flatRate(250, 250, 0);

        service.createDraft(ORDER_CODE);

        verify(calcService).calculateOnTaxableValue(anyString(), anyLong(), anyInt(), anyString(),
                anyString(), eq(LocalDate.of(2026, 8, 15)));
    }
}
