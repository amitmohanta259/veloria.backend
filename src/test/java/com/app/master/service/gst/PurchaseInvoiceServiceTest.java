package com.app.master.service.gst;

import com.app.master.service.core.entity.GstInputTaxEntity;
import com.app.master.service.core.entity.GstRegistrationEntity;
import com.app.master.service.core.entity.PurchaseInvoiceEntity;
import com.app.master.service.core.entity.PurchaseInvoiceItemEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.security.GstSecurityContext;
import com.app.master.service.repository.admin.GstInputTaxRepository;
import com.app.master.service.repository.admin.PurchaseInvoiceItemRepository;
import com.app.master.service.repository.admin.PurchaseInvoiceRepository;
import com.app.master.service.repository.admin.PurchaseOrderItemRepository;
import com.app.master.service.repository.admin.PurchaseOrderRepository;
import com.app.master.service.service.admin.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Recording a vendor invoice and turning it into claimable input tax.
 *
 * The rule under test throughout: the vendor's figures are recorded as stated,
 * but an invoice that contradicts itself is refused rather than stored.
 */
class PurchaseInvoiceServiceTest {

    private static final String VENDOR_SAME_STATE  = "21AAAAA0000A1Z5";  // Odisha
    private static final String VENDOR_OTHER_STATE = "27BBBBB1111B1Z5";  // Maharashtra
    private static final String OUR_GSTIN          = "21AABCU9603R1ZX";  // Odisha

    private com.app.master.service.repository.admin.SupplierRepository supplierRepo;
    private PurchaseOrderRepository purchaseOrderRepo;
    private PurchaseOrderItemRepository purchaseOrderItemRepo;
    private PurchaseInvoiceRepository invoiceRepo;
    private PurchaseInvoiceItemRepository itemRepo;
    private GstInputTaxRepository inputTaxRepo;
    private GstMovementService movementService;
    private GstIdentityService identity;
    private GstTaxPeriodService periodService;
    private PurchaseInvoiceService service;

    private final List<PurchaseInvoiceItemEntity> savedLines = new ArrayList<>();
    private final List<GstInputTaxEntity> savedInputTax = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        supplierRepo = mock(com.app.master.service.repository.admin.SupplierRepository.class);
        when(supplierRepo.findByUuid(any())).thenReturn(Optional.empty());
        purchaseOrderRepo = mock(PurchaseOrderRepository.class);
        purchaseOrderItemRepo = mock(PurchaseOrderItemRepository.class);
        invoiceRepo = mock(PurchaseInvoiceRepository.class);
        itemRepo = mock(PurchaseInvoiceItemRepository.class);
        inputTaxRepo = mock(GstInputTaxRepository.class);
        movementService = mock(GstMovementService.class);
        identity = mock(GstIdentityService.class);
        periodService = mock(GstTaxPeriodService.class);
        GstAuditService audit = mock(GstAuditService.class);
        GstSecurityContext ctx = mock(GstSecurityContext.class);

        when(ctx.current()).thenReturn(Optional.empty());
        when(ctx.actor()).thenReturn("tester");
        when(identity.defaultOrganizationId()).thenReturn(1L);
        when(identity.primaryRegistration(any())).thenReturn(Optional.of(
                GstRegistrationEntity.builder().id(1L).gstin(OUR_GSTIN).build()));
        when(identity.isValidGstin(anyString())).thenReturn(true);
        when(identity.stateCodeOf(anyString()))
                .thenAnswer(i -> i.getArgument(0, String.class).substring(0, 2));
        when(identity.isInterState(anyString(), anyString()))
                .thenAnswer(i -> !i.getArgument(0, String.class).equals(i.getArgument(1, String.class)));
        when(identity.supplyType(anyString(), anyString()))
                .thenAnswer(i -> i.getArgument(0, String.class).equals(i.getArgument(1, String.class))
                        ? "INTRA_STATE" : "INTER_STATE");

        when(invoiceRepo.findByOrganizationIdAndVendorGstinAndVendorInvoiceNumber(any(), any(), any()))
                .thenReturn(Optional.empty());

        savedLines.clear();
        savedInputTax.clear();

        AtomicLong ids = new AtomicLong(500);
        when(invoiceRepo.save(any())).thenAnswer(i -> {
            PurchaseInvoiceEntity e = i.getArgument(0);
            if (e.getId() == null) e.setId(ids.incrementAndGet());
            return e;
        });
        when(itemRepo.saveAll(any())).thenAnswer(i -> {
            List<PurchaseInvoiceItemEntity> l = i.getArgument(0);
            savedLines.addAll(l);
            return l;
        });
        when(inputTaxRepo.save(any())).thenAnswer(i -> {
            GstInputTaxEntity e = i.getArgument(0);
            if (e.getId() == null) e.setId(900L);
            savedInputTax.add(e);
            return e;
        });

        service = new PurchaseInvoiceService(supplierRepo, purchaseOrderRepo, purchaseOrderItemRepo,
                invoiceRepo, itemRepo, inputTaxRepo, movementService,
                identity, periodService, new GstRoundingService(), audit, ctx);
    }

    private PurchaseInvoiceService.Line intraLine(long unitPaise, int qty, long cgst, long sgst) {
        return new PurchaseInvoiceService.Line("Fabric", "Cotton", "5208", qty, "MTR",
                unitPaise, 0L, 1800, cgst, sgst, 0L, 0L);
    }

    private PurchaseInvoiceService.VendorInvoice invoice(String gstin,
                                                        List<PurchaseInvoiceService.Line> lines) {
        return new PurchaseInvoiceService.VendorInvoice(gstin, "Acme Textiles", "VINV-1",
                LocalDate.of(2026, 9, 5), null, false, lines);
    }

    // ── Recording ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An intra-state vendor invoice records lines, totals and input tax")
    void recordsIntraStateInvoice() throws Exception {
        PurchaseInvoiceEntity inv = service.record(
                invoice(VENDOR_SAME_STATE, List.of(intraLine(100000L, 2, 18000L, 18000L))));

        assertEquals("RECORDED", inv.getStatus());
        assertEquals("INTRA_STATE", inv.getSupplyType());
        assertEquals(200000L, inv.getTaxableValue(), "2 units at ₹1,000");
        assertEquals(18000L, inv.getCgstAmount());
        assertEquals(18000L, inv.getSgstAmount());
        assertEquals(0L, inv.getIgstAmount());
        assertEquals(36000L, inv.getTotalTax());
        assertEquals(236000L, inv.getTotalInvoiceValue());
        assertEquals("2026-09", inv.getTaxPeriod());
        assertEquals(1, savedLines.size());
    }

    @Test
    @DisplayName("The input tax record is created and linked, so credit always has a purchase behind it")
    void createsAndLinksInputTax() throws Exception {
        PurchaseInvoiceEntity inv = service.record(
                invoice(VENDOR_SAME_STATE, List.of(intraLine(100000L, 1, 9000L, 9000L))));

        assertEquals(1, savedInputTax.size());
        GstInputTaxEntity it = savedInputTax.get(0);
        assertEquals("VINV-1", it.getInvoiceNumber());
        assertEquals(18000L, it.getTotalInputTax());
        assertEquals("PENDING_REVIEW", it.getItcStatus(), "credit is not claimable until reviewed");
        assertEquals(it.getId(), inv.getInputTaxId(), "the invoice points at its credit record");
    }

    @Test
    @DisplayName("The inward ledger movement is posted through the existing movement service")
    void postsThroughExistingMovementService() throws Exception {
        service.record(invoice(VENDOR_SAME_STATE, List.of(intraLine(100000L, 1, 9000L, 9000L))));

        verify(movementService, times(1)).recordPurchaseMovement(any(GstInputTaxEntity.class));
    }

    @Test
    @DisplayName("An inter-state vendor invoice records IGST")
    void recordsInterStateInvoice() throws Exception {
        var line = new PurchaseInvoiceService.Line("Fabric", null, "5208", 1, "MTR",
                100000L, 0L, 1800, 0L, 0L, 18000L, 0L);

        PurchaseInvoiceEntity inv = service.record(invoice(VENDOR_OTHER_STATE, List.of(line)));

        assertEquals("INTER_STATE", inv.getSupplyType());
        assertEquals(18000L, inv.getIgstAmount());
        assertEquals(0L, inv.getCgstAmount());
    }

    @Test
    @DisplayName("Line discount reduces the taxable value")
    void discountReducesTaxableValue() throws Exception {
        var line = new PurchaseInvoiceService.Line("Fabric", null, "5208", 2, "MTR",
                100000L, 50000L, 1800, 13500L, 13500L, 0L, 0L);

        PurchaseInvoiceEntity inv = service.record(invoice(VENDOR_SAME_STATE, List.of(line)));

        assertEquals(200000L, inv.getGrossValue());
        assertEquals(50000L, inv.getDiscountValue());
        assertEquals(150000L, inv.getTaxableValue());
    }

    @Test
    @DisplayName("Multiple lines are summed into the invoice totals")
    void sumsMultipleLines() throws Exception {
        PurchaseInvoiceEntity inv = service.record(invoice(VENDOR_SAME_STATE, List.of(
                intraLine(100000L, 1, 9000L, 9000L),
                intraLine(50000L, 2, 9000L, 9000L))));

        assertEquals(200000L, inv.getTaxableValue());
        assertEquals(18000L, inv.getCgstAmount());
        assertEquals(36000L, inv.getTotalTax());
        assertEquals(2, savedLines.size());
    }

    // ── Refusals ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An inter-state supply carrying CGST/SGST is refused, not recorded")
    void interStateWithCgstIsRefused() {
        var contradictory = new PurchaseInvoiceService.Line("Fabric", null, "5208", 1, "MTR",
                100000L, 0L, 1800, 9000L, 9000L, 0L, 0L);

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> service.record(invoice(VENDOR_OTHER_STATE, List.of(contradictory))));
        assertTrue(e.getMessage().contains("inter-state supply but carries CGST/SGST"));
        assertTrue(savedInputTax.isEmpty(), "no credit record may survive a refusal");
    }

    @Test
    @DisplayName("An intra-state supply carrying IGST is refused")
    void intraStateWithIgstIsRefused() {
        var contradictory = new PurchaseInvoiceService.Line("Fabric", null, "5208", 1, "MTR",
                100000L, 0L, 1800, 0L, 0L, 18000L, 0L);

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> service.record(invoice(VENDOR_SAME_STATE, List.of(contradictory))));
        assertTrue(e.getMessage().contains("intra-state supply but carries IGST"));
    }

    @Test
    @DisplayName("The same vendor invoice cannot be recorded twice")
    void duplicateVendorInvoiceIsRefused() {
        when(invoiceRepo.findByOrganizationIdAndVendorGstinAndVendorInvoiceNumber(any(), any(), any()))
                .thenReturn(Optional.of(PurchaseInvoiceEntity.builder().id(77L).build()));

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> service.record(invoice(VENDOR_SAME_STATE, List.of(intraLine(100000L, 1, 9000L, 9000L)))));
        assertTrue(e.getMessage().contains("double the credit"));
    }

    @Test
    @DisplayName("A missing vendor GSTIN is refused — there is no credit without it")
    void missingVendorGstinIsRefused() {
        var v = new PurchaseInvoiceService.VendorInvoice(null, "Acme", "VINV-2",
                LocalDate.of(2026, 9, 5), null, false, List.of(intraLine(100000L, 1, 9000L, 9000L)));

        VeloriaException e = assertThrows(VeloriaException.class, () -> service.record(v));
        assertTrue(e.getMessage().contains("vendor GSTIN is required"));
    }

    @Test
    @DisplayName("A structurally invalid vendor GSTIN is refused")
    void invalidVendorGstinIsRefused() {
        when(identity.isValidGstin(anyString())).thenReturn(false);

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> service.record(invoice("NOTAGSTIN", List.of(intraLine(100000L, 1, 9000L, 9000L)))));
        assertTrue(e.getMessage().contains("not structurally valid"));
    }

    @Test
    @DisplayName("An invoice with no lines is refused")
    void noLinesIsRefused() {
        VeloriaException e = assertThrows(VeloriaException.class,
                () -> service.record(invoice(VENDOR_SAME_STATE, List.of())));
        assertTrue(e.getMessage().contains("at least one line"));
    }

    @Test
    @DisplayName("A missing invoice date is refused — it determines the tax period")
    void missingDateIsRefused() {
        var v = new PurchaseInvoiceService.VendorInvoice(VENDOR_SAME_STATE, "Acme", "VINV-3",
                null, null, false, List.of(intraLine(100000L, 1, 9000L, 9000L)));

        VeloriaException e = assertThrows(VeloriaException.class, () -> service.record(v));
        assertTrue(e.getMessage().contains("tax period"));
    }

    @Test
    @DisplayName("A negative tax amount is refused")
    void negativeTaxIsRefused() {
        var line = new PurchaseInvoiceService.Line("Fabric", null, "5208", 1, "MTR",
                100000L, 0L, 1800, -9000L, 9000L, 0L, 0L);

        assertThrows(VeloriaException.class,
                () -> service.record(invoice(VENDOR_SAME_STATE, List.of(line))));
    }

    @Test
    @DisplayName("A discount larger than the line is refused")
    void oversizedDiscountIsRefused() {
        var line = new PurchaseInvoiceService.Line("Fabric", null, "5208", 1, "MTR",
                100000L, 200000L, 1800, 0L, 0L, 0L, 0L);

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> service.record(invoice(VENDOR_SAME_STATE, List.of(line))));
        assertTrue(e.getMessage().contains("discount larger than the line"));
    }

    @Test
    @DisplayName("A zero quantity line is refused")
    void zeroQuantityIsRefused() {
        var line = new PurchaseInvoiceService.Line("Fabric", null, "5208", 0, "MTR",
                100000L, 0L, 1800, 0L, 0L, 0L, 0L);

        assertThrows(VeloriaException.class,
                () -> service.record(invoice(VENDOR_SAME_STATE, List.of(line))));
    }

    @Test
    @DisplayName("A closed tax period blocks recording a purchase into it")
    void closedPeriodBlocksRecording() throws Exception {
        doThrow(new VeloriaException(com.app.master.service.core.response.ResponseCode.CONFLICT,
                "GST period 2026-09 is LOCKED"))
                .when(periodService).assertOpen("2026-09");

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> service.record(invoice(VENDOR_SAME_STATE, List.of(intraLine(100000L, 1, 9000L, 9000L)))));
        assertTrue(e.getMessage().contains("LOCKED"));
    }

    @Test
    @DisplayName("Tax heads on every recorded line sum to that line's total")
    void lineTaxHeadsSumToLineTotal() throws Exception {
        service.record(invoice(VENDOR_SAME_STATE, List.of(intraLine(100000L, 1, 9000L, 9000L))));

        PurchaseInvoiceItemEntity line = savedLines.get(0);
        assertEquals(line.getCgstAmount() + line.getSgstAmount()
                        + line.getIgstAmount() + line.getCessAmount(),
                line.getTotalTax());
        assertEquals(line.getTaxableValue() + line.getTotalTax(), line.getTotalValue());
    }

    // ── From a purchase order (phase 11) ─────────────────────────────────────

    private com.app.master.service.core.entity.PurchaseOrderEntity po(
            String gstin, Long cgst, Long sgst, Long igst, Long taxable) {
        var e = com.app.master.service.core.entity.PurchaseOrderEntity.builder()
                .id(7L).poCode("PO-2026-0007").vendorGstin(gstin)
                .vendorInvoiceNumber("VINV-PO").vendorInvoiceDate(LocalDate.of(2026, 9, 3))
                .taxableAmount(taxable).cgstAmount(cgst).sgstAmount(sgst).igstAmount(igst)
                .gstExtractionStatus("EXTRACTED")
                .build();
        when(purchaseOrderRepo.findById(7L)).thenReturn(Optional.of(e));
        when(invoiceRepo.findByPurchaseOrderIdOrderByIdAsc(7L)).thenReturn(List.of());
        return e;
    }

    private void poItems(long... lineTotals) {
        List<com.app.master.service.core.entity.PurchaseOrderItemEntity> items = new ArrayList<>();
        long id = 1;
        for (long t : lineTotals) {
            items.add(com.app.master.service.core.entity.PurchaseOrderItemEntity.builder()
                    .id(id++).purchaseOrderId(7L).productName("Item " + id)
                    .quantity(1).unitCost(t).lineTotal(t).hsnCode("5208").build());
        }
        when(purchaseOrderItemRepo.findByPurchaseOrderIdOrderByIdAsc(7L)).thenReturn(items);
    }

    @Test
    @DisplayName("A purchase order's captured vendor GST becomes input credit unchanged")
    void recordsFromPurchaseOrder() throws Exception {
        po(VENDOR_SAME_STATE, 450000L, 450000L, 0L, 5000000L);
        poItems(5000000L);

        PurchaseInvoiceEntity inv = service.recordFromPurchaseOrder(7L);

        assertEquals("VINV-PO", inv.getVendorInvoiceNumber());
        assertEquals(5000000L, inv.getTaxableValue(), "the vendor's taxable value, not ours");
        assertEquals(450000L, inv.getCgstAmount());
        assertEquals(450000L, inv.getSgstAmount());
        assertEquals(900000L, inv.getTotalTax(), "exactly the tax the vendor charged");
        assertEquals(7L, inv.getPurchaseOrderId(), "the credit is traceable to the order");
    }

    @Test
    @DisplayName("Header tax is apportioned across lines and sums back to the vendor's total")
    void apportionsAcrossLinesWithoutDrift() throws Exception {
        po(VENDOR_SAME_STATE, 450000L, 450000L, 0L, 5000000L);
        poItems(7500000L, 15000000L, 6000000L);   // three lines of differing value

        PurchaseInvoiceEntity inv = service.recordFromPurchaseOrder(7L);

        assertEquals(3, savedLines.size());
        assertEquals(450000L, savedLines.stream().mapToLong(PurchaseInvoiceItemEntity::getCgstAmount).sum(),
                "apportioned CGST sums back exactly");
        assertEquals(450000L, savedLines.stream().mapToLong(PurchaseInvoiceItemEntity::getSgstAmount).sum());
        assertEquals(5000000L, inv.getTaxableValue(), "and so does the taxable value");
    }

    @Test
    @DisplayName("An inter-state purchase order records IGST")
    void interStatePurchaseOrder() throws Exception {
        po(VENDOR_OTHER_STATE, 0L, 0L, 900000L, 5000000L);
        poItems(5000000L);

        PurchaseInvoiceEntity inv = service.recordFromPurchaseOrder(7L);

        assertEquals("INTER_STATE", inv.getSupplyType());
        assertEquals(900000L, inv.getIgstAmount());
        assertEquals(0L, inv.getCgstAmount());
    }

    @Test
    @DisplayName("A purchase order with no vendor GSTIN cannot become credit")
    void purchaseOrderWithoutGstinIsRefused() {
        po(null, 450000L, 450000L, 0L, 5000000L);
        poItems(5000000L);

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> service.recordFromPurchaseOrder(7L));
        assertTrue(e.getMessage().contains("no vendor GSTIN"));
    }

    @Test
    @DisplayName("A purchase order with no captured GST is refused — there is nothing to claim")
    void purchaseOrderWithoutTaxIsRefused() {
        po(VENDOR_SAME_STATE, 0L, 0L, 0L, 0L);
        poItems(5000000L);

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> service.recordFromPurchaseOrder(7L));
        assertTrue(e.getMessage().contains("nothing"));
    }

    @Test
    @DisplayName("A purchase order with no vendor invoice number is refused")
    void purchaseOrderWithoutInvoiceNumberIsRefused() {
        var e = po(VENDOR_SAME_STATE, 450000L, 450000L, 0L, 5000000L);
        e.setVendorInvoiceNumber(null);
        poItems(5000000L);

        VeloriaException ex = assertThrows(VeloriaException.class,
                () -> service.recordFromPurchaseOrder(7L));
        assertTrue(ex.getMessage().contains("invoice number"));
    }

    @Test
    @DisplayName("An unknown purchase order is reported, not silently skipped")
    void unknownPurchaseOrderIsReported() {
        when(purchaseOrderRepo.findById(404L)).thenReturn(Optional.empty());
        assertThrows(VeloriaException.class, () -> service.recordFromPurchaseOrder(404L));
    }

    @Test
    @DisplayName("Only orders with vendor GST and no invoice yet are listed as awaiting")
    void awaitingListFiltersCorrectly() {
        var ready = po(VENDOR_SAME_STATE, 450000L, 450000L, 0L, 5000000L);
        var noGstin = com.app.master.service.core.entity.PurchaseOrderEntity.builder()
                .id(8L).poCode("PO-8").cgstAmount(100L).build();
        var noTax = com.app.master.service.core.entity.PurchaseOrderEntity.builder()
                .id(9L).poCode("PO-9").vendorGstin(VENDOR_SAME_STATE).build();
        when(purchaseOrderRepo.findByArchiveFalseOrderByCreatedDesc())
                .thenReturn(List.of(ready, noGstin, noTax));

        var awaiting = service.ordersAwaitingInvoice();

        assertEquals(1, awaiting.size());
        assertEquals("PO-2026-0007", awaiting.get(0).getPoCode());
    }

    @Test
    @DisplayName("An order already turned into an invoice is not offered again")
    void alreadyInvoicedOrderIsNotAwaiting() {
        var ready = po(VENDOR_SAME_STATE, 450000L, 450000L, 0L, 5000000L);
        when(invoiceRepo.findByPurchaseOrderIdOrderByIdAsc(7L))
                .thenReturn(List.of(PurchaseInvoiceEntity.builder().id(1L).build()));
        when(purchaseOrderRepo.findByArchiveFalseOrderByCreatedDesc()).thenReturn(List.of(ready));

        assertTrue(service.ordersAwaitingInvoice().isEmpty());
    }
}
