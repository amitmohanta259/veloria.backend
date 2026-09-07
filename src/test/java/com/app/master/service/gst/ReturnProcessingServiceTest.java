package com.app.master.service.gst;

import com.app.master.service.core.entity.*;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.repository.admin.*;
import com.app.master.service.service.admin.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Return scenarios 13-24 from spec section 50.
 *
 * The order under test is the one the spec uses as its example:
 *   Dress A 1000, Dress B 2000, Dress C 3000 (paise figures scaled up)
 * plus a multi-quantity line for partial-quantity cases.
 */
class ReturnProcessingServiceTest {

    private CustomerOrderRepository orderRepo;
    private CustomerOrderItemRepository itemRepo;
    private OrderReturnRequestRepository requestRepo;
    private OrderReturnItemRepository returnItemRepo;
    private InventoryProductRepository productRepo;
    private GstCreditNoteService creditNoteService;
    private GstAuditService auditService;
    private ReturnProcessingService service;

    private CustomerOrderEntity order;
    private CustomerOrderItemEntity dressA, dressB, dressC;
    private final List<OrderReturnItemEntity> savedReturnItems = new ArrayList<>();

    @BeforeEach
    void setUp() {
        orderRepo = mock(CustomerOrderRepository.class);
        itemRepo = mock(CustomerOrderItemRepository.class);
        requestRepo = mock(OrderReturnRequestRepository.class);
        returnItemRepo = mock(OrderReturnItemRepository.class);
        productRepo = mock(InventoryProductRepository.class);
        creditNoteService = mock(GstCreditNoteService.class);
        auditService = mock(GstAuditService.class);

        service = new ReturnProcessingService(orderRepo, itemRepo, requestRepo, returnItemRepo,
                productRepo, new GstRoundingService(), creditNoteService, auditService);

        order = CustomerOrderEntity.builder()
                .id(1L).orderCode("ORD-1001").customerId("cust-1")
                .customerName("Test Customer")
                .status("DELIVERED")
                .orderPlacedAt(Instant.parse("2026-08-15T10:00:00Z"))
                .placeOfSupply("21").sellerStateCode("21").buyerStateCode("21")
                .taxableValue(600000L).cgstAmount(15000L).sgstAmount(15000L).igstAmount(0L)
                .totalTaxAmount(30000L)
                .build();

        // 5% GST: 2.5% CGST + 2.5% SGST
        dressA = item(101L, 100000L, 1, 2500L);
        dressB = item(102L, 200000L, 1, 5000L);
        dressC = item(103L, 300000L, 1, 7500L);

        when(orderRepo.findByOrderCodeAndArchiveFalse("ORD-1001")).thenReturn(Optional.of(order));
        when(itemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L))
                .thenReturn(List.of(dressA, dressB, dressC));
        when(productRepo.findByUuid(any())).thenReturn(Optional.empty());
        when(requestRepo.findByOrderCodeAndArchiveFalseOrderByIdAsc(any())).thenReturn(List.of());

        AtomicLong requestIds = new AtomicLong(500);
        when(requestRepo.save(any())).thenAnswer(inv -> {
            OrderReturnRequestEntity r = inv.getArgument(0);
            if (r.getId() == null) r.setId(requestIds.incrementAndGet());
            return r;
        });

        AtomicLong itemIds = new AtomicLong(900);
        savedReturnItems.clear();
        when(returnItemRepo.save(any())).thenAnswer(inv -> {
            OrderReturnItemEntity r = inv.getArgument(0);
            if (r.getId() == null) r.setId(itemIds.incrementAndGet());
            savedReturnItems.add(r);
            return r;
        });
        when(returnItemRepo.totalReturnedQuantity(anyLong())).thenReturn(0);
        when(returnItemRepo.totalReversedTaxable(anyLong())).thenReturn(0L);
        when(returnItemRepo.totalReversedCgst(anyLong())).thenReturn(0L);
        when(returnItemRepo.totalReversedSgst(anyLong())).thenReturn(0L);
        when(returnItemRepo.totalReversedIgst(anyLong())).thenReturn(0L);
        when(returnItemRepo.findByOrderItemIdOrderByIdAsc(anyLong())).thenReturn(List.of());

        when(creditNoteService.createForReturn(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    List<OrderReturnItemEntity> lines = inv.getArgument(2);
                    long taxable = lines.stream().mapToLong(OrderReturnItemEntity::getTaxableValuePaise).sum();
                    long tax = lines.stream().mapToLong(OrderReturnItemEntity::getTotalTaxPaise).sum();
                    return Optional.of(GstCreditNoteEntity.builder()
                            .id(77L).creditNoteNumber("CN-TEST-0001")
                            .taxableValuePaise(taxable).totalCreditPaise(tax)
                            .build());
                });
    }

    private CustomerOrderItemEntity item(long id, long unitPrice, int qty, long cgstAndSgstEach) {
        return CustomerOrderItemEntity.builder()
                .id(id).customerOrderId(1L).productUuid(UUID.randomUUID())
                .unitPricePaise(unitPrice).quantity(qty).returnedQuantity(0)
                .taxableValuePaise(unitPrice * qty)
                .hsnCode("6204")
                .cgstRateBp(250).sgstRateBp(250).igstRateBp(0)
                .cgstAmount(cgstAndSgstEach).sgstAmount(cgstAndSgstEach).igstAmount(0L)
                .totalTaxPaise(cgstAndSgstEach * 2)
                .build();
    }

    @Test
    @DisplayName("Scenario 14: returning only Dress B reverses only Dress B's GST")
    void partialItemReturnReversesOnlyThatItem() throws VeloriaException {
        var result = service.verify("ORD-1001",
                List.of(new ReturnProcessingService.ReturnLine(102L, 1, "PRODUCT_OK")),
                "Only dress B came back", true, null, "ADMIN");

        assertEquals(1, result.linesReturned());
        assertEquals(1, result.unitsReturned());

        assertEquals(1, savedReturnItems.size());
        OrderReturnItemEntity line = savedReturnItems.get(0);
        assertEquals(102L, line.getOrderItemId());
        assertEquals(200000L, line.getTaxableValuePaise(), "Only Dress B's taxable value");
        assertEquals(5000L, line.getCgstAmountPaise());
        assertEquals(5000L, line.getSgstAmountPaise());
        assertEquals(10000L, line.getTotalTaxPaise());

        // The whole order's 30000 paise of tax must NOT be reversed
        assertNotEquals(30000L, line.getTotalTaxPaise(),
                "Must not reverse the entire order's GST for a single-item return");

        assertEquals("PARTIALLY_RETURNED", result.orderStatus());
    }

    @Test
    @DisplayName("Scenario 15: returning 2 of 5 units reverses two units' worth of GST")
    void partialQuantityReturn() throws VeloriaException {
        CustomerOrderItemEntity fivePack = item(201L, 100000L, 5, 12500L);
        when(itemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L)).thenReturn(List.of(fivePack));

        var result = service.verify("ORD-1001",
                List.of(new ReturnProcessingService.ReturnLine(201L, 2, "PRODUCT_OK")),
                null, true, null, "ADMIN");

        OrderReturnItemEntity line = savedReturnItems.get(0);
        assertEquals(2, line.getQuantity());
        assertEquals(200000L, line.getTaxableValuePaise(), "2 of 5 units of 500000 total");
        assertEquals(5000L, line.getCgstAmountPaise(), "2/5 of 12500");
        assertEquals(5000L, line.getSgstAmountPaise());
        assertEquals(2, result.unitsReturned());
        assertEquals("PARTIALLY_RETURNED", result.orderStatus());
        assertEquals(2, fivePack.getReturnedQuantity());
        assertEquals(3, fivePack.remainingReturnableQuantity());
    }

    @Test
    @DisplayName("Scenario 16: repeat returns never reverse the same GST twice")
    void multipleReturnsDoNotDoubleReverse() throws VeloriaException {
        CustomerOrderItemEntity fivePack = item(201L, 100000L, 5, 12500L);
        when(itemRepo.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L)).thenReturn(List.of(fivePack));

        // First return: 1 unit
        service.verify("ORD-1001",
                List.of(new ReturnProcessingService.ReturnLine(201L, 1, "PRODUCT_OK")),
                null, true, null, "ADMIN");
        long firstCgst = savedReturnItems.get(0).getCgstAmountPaise();

        // Second return: 2 units, with the first already recorded
        when(returnItemRepo.totalReturnedQuantity(201L)).thenReturn(1);
        when(returnItemRepo.totalReversedCgst(201L)).thenReturn(firstCgst);
        when(returnItemRepo.totalReversedSgst(201L)).thenReturn(firstCgst);
        when(returnItemRepo.totalReversedTaxable(201L)).thenReturn(100000L);
        savedReturnItems.clear();

        service.verify("ORD-1001",
                List.of(new ReturnProcessingService.ReturnLine(201L, 2, "PRODUCT_OK")),
                null, true, null, "ADMIN");
        long secondCgst = savedReturnItems.get(0).getCgstAmountPaise();

        assertEquals(2500L, firstCgst, "1 of 5 units of 12500");
        assertEquals(5000L, secondCgst, "2 of 5 units of 12500");
        assertTrue(firstCgst + secondCgst <= 12500L,
                "Cumulative reversal must never exceed the original GST");
        assertEquals(3, fivePack.getReturnedQuantity());
    }

    @Test
    @DisplayName("Scenario 24: returning more than was bought is rejected")
    void returnQuantityExceededIsRejected() {
        VeloriaException ex = assertThrows(VeloriaException.class, () ->
                service.verify("ORD-1001",
                        List.of(new ReturnProcessingService.ReturnLine(102L, 5, "PRODUCT_OK")),
                        null, true, null, "ADMIN"));
        assertTrue(ex.getMessage().contains("remain returnable"), ex.getMessage());
        assertTrue(savedReturnItems.isEmpty(), "Nothing may be written when validation fails");
    }

    @Test
    @DisplayName("Returning more than remains after a prior return is rejected")
    void cannotExceedRemainingAfterPriorReturn() {
        dressB.setReturnedQuantity(1); // already fully returned
        VeloriaException ex = assertThrows(VeloriaException.class, () ->
                service.verify("ORD-1001",
                        List.of(new ReturnProcessingService.ReturnLine(102L, 1, "PRODUCT_OK")),
                        null, true, null, "ADMIN"));
        assertTrue(ex.getMessage().contains("only 0"), ex.getMessage());
    }

    @Test
    @DisplayName("An order item from a different order is rejected")
    void foreignOrderItemRejected() {
        assertThrows(VeloriaException.class, () ->
                service.verify("ORD-1001",
                        List.of(new ReturnProcessingService.ReturnLine(999L, 1, "PRODUCT_OK")),
                        null, true, null, "ADMIN"));
    }

    @Test
    @DisplayName("Zero or negative return quantity is rejected")
    void nonPositiveQuantityRejected() {
        assertThrows(VeloriaException.class, () ->
                service.verify("ORD-1001",
                        List.of(new ReturnProcessingService.ReturnLine(102L, 0, "PRODUCT_OK")),
                        null, true, null, "ADMIN"));
        assertThrows(VeloriaException.class, () ->
                service.verify("ORD-1001",
                        List.of(new ReturnProcessingService.ReturnLine(102L, -1, "PRODUCT_OK")),
                        null, true, null, "ADMIN"));
    }

    @Test
    @DisplayName("Scenario 13: returning every line marks the order RETURNED")
    void fullReturnMarksOrderReturned() throws VeloriaException {
        var result = service.verify("ORD-1001",
                List.of(new ReturnProcessingService.ReturnLine(101L, 1, "PRODUCT_OK"),
                        new ReturnProcessingService.ReturnLine(102L, 1, "PRODUCT_OK"),
                        new ReturnProcessingService.ReturnLine(103L, 1, "PRODUCT_OK")),
                null, true, null, "ADMIN");

        assertEquals("RETURNED", result.orderStatus());
        assertEquals(3, result.linesReturned());
        long totalTax = savedReturnItems.stream().mapToLong(OrderReturnItemEntity::getTotalTaxPaise).sum();
        assertEquals(30000L, totalTax, "Full return reverses exactly the order's GST");
    }

    @Test
    @DisplayName("Scenario 20: a damaged return can still raise a credit note")
    void damagedStillAdjustsGstWhenRequired() throws VeloriaException {
        var result = service.verify("ORD-1001",
                List.of(new ReturnProcessingService.ReturnLine(102L, 1, "DAMAGED")),
                "Item arrived damaged", true, null, "ADMIN");

        assertEquals("COMPLETED", result.gstAdjustmentStatus(),
                "Physical damage must not by itself block the GST adjustment");
        assertEquals("DAMAGED", savedReturnItems.get(0).getReturnCondition(),
                "Condition still drives inventory");
    }

    @Test
    @DisplayName("GST adjustment can be declined independently of condition")
    void gstAdjustmentCanBeDeclined() throws VeloriaException {
        var result = service.verify("ORD-1001",
                List.of(new ReturnProcessingService.ReturnLine(102L, 1, "PRODUCT_OK")),
                null, false, "No commercial reversal — goodwill replacement", "ADMIN");

        assertEquals("NOT_APPLICABLE", result.gstAdjustmentStatus());
        assertNull(result.creditNoteNumber());
        verify(creditNoteService, never()).createForReturn(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A credit note failure is recorded, not swallowed")
    void creditNoteFailureIsRecorded() throws VeloriaException {
        // doThrow, not when(...).thenThrow: the latter would invoke the mock
        // with null arguments and trip the setUp answer.
        doThrow(new RuntimeException("ledger unavailable"))
                .when(creditNoteService).createForReturn(any(), any(), any(), any());

        var result = service.verify("ORD-1001",
                List.of(new ReturnProcessingService.ReturnLine(102L, 1, "PRODUCT_OK")),
                null, true, null, "ADMIN");

        assertEquals("FAILED", result.gstAdjustmentStatus());
        verify(auditService).recordException(eq("RETURN_CREDIT_NOTE"), eq("RETURN_REQUEST"),
                any(), any(), any(Exception.class));
    }

    @Test
    @DisplayName("Omitting items returns everything still outstanding")
    void emptyLinesReturnsRemainder() throws VeloriaException {
        dressA.setReturnedQuantity(1); // already back
        var result = service.verify("ORD-1001", List.of(), null, true, null, "ADMIN");

        assertEquals(2, result.linesReturned(), "Only B and C remain");
        assertEquals("RETURNED", result.orderStatus());
    }

    @Test
    @DisplayName("Verifying with nothing outstanding is rejected")
    void nothingLeftToReturn() {
        dressA.setReturnedQuantity(1);
        dressB.setReturnedQuantity(1);
        dressC.setReturnedQuantity(1);
        assertThrows(VeloriaException.class,
                () -> service.verify("ORD-1001", List.of(), null, true, null, "ADMIN"));
    }

    @Test
    @DisplayName("Return lines carry the original snapshot's rate, not a recalculated one")
    void usesOriginalSnapshotRate() throws VeloriaException {
        service.verify("ORD-1001",
                List.of(new ReturnProcessingService.ReturnLine(102L, 1, "PRODUCT_OK")),
                null, true, null, "ADMIN");

        OrderReturnItemEntity line = savedReturnItems.get(0);
        assertEquals(250, line.getCgstRateBp(), "Rate comes from the original order item");
        assertEquals(250, line.getSgstRateBp());
        assertEquals("6204", line.getHsnCode());
    }

    @Test
    @DisplayName("An unknown order code is rejected")
    void unknownOrderRejected() {
        when(orderRepo.findByOrderCodeAndArchiveFalse("NOPE")).thenReturn(Optional.empty());
        assertThrows(VeloriaException.class,
                () -> service.verify("NOPE", List.of(), null, true, null, "ADMIN"));
    }

    @Test
    @DisplayName("The credit note receives exactly the returned lines")
    void creditNoteGetsReturnedLinesOnly() throws VeloriaException {
        service.verify("ORD-1001",
                List.of(new ReturnProcessingService.ReturnLine(102L, 1, "PRODUCT_OK")),
                null, true, null, "ADMIN");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderReturnItemEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(creditNoteService).createForReturn(any(), any(), captor.capture(), any());

        assertEquals(1, captor.getValue().size());
        assertEquals(102L, captor.getValue().get(0).getOrderItemId());
    }
}
