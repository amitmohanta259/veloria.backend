package com.app.master.service.gst;

import com.app.master.service.core.entity.GstRegistrationEntity;
import com.app.master.service.core.entity.SalesInvoiceEntity;
import com.app.master.service.core.entity.SalesInvoiceItemEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.security.GstSecurityContext;
import com.app.master.service.repository.admin.SalesInvoiceItemRepository;
import com.app.master.service.repository.admin.SalesInvoiceRepository;
import com.app.master.service.service.admin.GstAuditService;
import com.app.master.service.service.admin.GstIdentityService;
import com.app.master.service.service.admin.GstInvoicePdfService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Rendering a tax invoice.
 *
 * The rule under test: the PDF reproduces the stored snapshot and computes
 * nothing, so a later rate change cannot alter a document already issued.
 */
class GstInvoicePdfServiceTest {

    private SalesInvoiceRepository invoiceRepo;
    private SalesInvoiceItemRepository itemRepo;
    private GstInvoicePdfService service;

    @BeforeEach
    void setUp() {
        invoiceRepo = mock(SalesInvoiceRepository.class);
        itemRepo = mock(SalesInvoiceItemRepository.class);
        GstIdentityService identity = mock(GstIdentityService.class);
        GstAuditService audit = mock(GstAuditService.class);
        GstSecurityContext ctx = mock(GstSecurityContext.class);

        when(ctx.current()).thenReturn(Optional.empty());
        when(ctx.actor()).thenReturn("tester");
        when(identity.defaultOrganizationId()).thenReturn(1L);
        when(identity.primaryRegistration(any())).thenReturn(Optional.of(
                GstRegistrationEntity.builder().id(1L).gstin("21AABCU9603R1ZX")
                        .legalName("Veloria Retail Private Limited").build()));
        when(itemRepo.findBySalesInvoiceIdOrderByLineNumberAsc(anyLong())).thenReturn(List.of(line()));

        service = new GstInvoicePdfService(invoiceRepo, itemRepo, identity, audit, ctx);
    }

    private SalesInvoiceItemEntity line() {
        return SalesInvoiceItemEntity.builder()
                .id(1L).lineNumber(1).productName("Mini Leather Backpack").hsnCode("4202")
                .quantity(2).unitPrice(100000L).taxableValue(200000L)
                .gstRateBp(1800).cgstAmount(18000L).sgstAmount(18000L).igstAmount(0L)
                .totalTax(36000L).totalValue(236000L)
                .build();
    }

    private SalesInvoiceEntity invoice(String status) {
        SalesInvoiceEntity inv = SalesInvoiceEntity.builder()
                .id(1L).organizationId(1L).status(status)
                .invoiceNumber("FY26-27/INV/000007")
                .invoiceDate(LocalDate.of(2026, 9, 5))
                .customerName("Acme Trading").customerType("B2B")
                .customerGstin("27BBBBB1111B1Z5")
                .billingAddressSnapshot("14 Marine Drive, Mumbai 400001")
                .placeOfSupply("27").sellerGstin("21AABCU9603R1ZX").sellerStateCode("21")
                .supplyType("INTER_STATE").taxPeriod("2026-09")
                .grossValue(200000L).taxableValue(200000L)
                .cgstAmount(0L).sgstAmount(0L).igstAmount(36000L)
                .totalTax(36000L).totalInvoiceValue(236000L)
                .build();
        when(invoiceRepo.findById(1L)).thenReturn(Optional.of(inv));
        return inv;
    }

    private String textOf(byte[] pdf) throws Exception {
        try (var doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An issued invoice renders a PDF named after its invoice number")
    void rendersIssuedInvoice() throws Exception {
        invoice("ISSUED");

        var rendered = service.render(1L);

        assertTrue(rendered.content().length > 0);
        assertEquals("FY26-27-INV-000007.pdf", rendered.fileName(),
                "the filename is derived from the invoice number, path-safe");
        assertEquals("%PDF", new String(rendered.content(), 0, 4));
    }

    @Test
    @DisplayName("The PDF carries the identifiers a tax invoice must show")
    void carriesMandatoryFields() throws Exception {
        invoice("ISSUED");

        String text = textOf(service.render(1L).content());

        assertTrue(text.contains("TAX INVOICE"));
        assertTrue(text.contains("FY26-27/INV/000007"), "invoice number");
        assertTrue(text.contains("21AABCU9603R1ZX"), "supplier GSTIN");
        assertTrue(text.contains("27BBBBB1111B1Z5"), "customer GSTIN");
        assertTrue(text.contains("Acme Trading"), "customer name");
        assertTrue(text.contains("4202"), "HSN");
        assertTrue(text.contains("Place of supply"));
        assertTrue(text.contains("Mumbai"), "billing address snapshot");
    }

    @Test
    @DisplayName("Only the tax heads that apply are printed")
    void printsOnlyApplicableHeads() throws Exception {
        invoice("ISSUED");   // inter-state: IGST only

        String text = textOf(service.render(1L).content());

        assertTrue(text.contains("IGST"));
        assertFalse(text.contains("CGST"), "an inter-state invoice must not show an empty CGST line");
        assertFalse(text.contains("SGST"));
    }

    @Test
    @DisplayName("Figures come from the snapshot, so the stored total is what appears")
    void reproducesStoredFigures() throws Exception {
        SalesInvoiceEntity inv = invoice("ISSUED");
        inv.setTotalInvoiceValue(999999L);   // whatever is stored is what prints

        String text = textOf(service.render(1L).content());

        assertTrue(text.contains("9,999.99"),
                "the stored total is printed as-is, never recomputed from the lines");
    }

    @Test
    @DisplayName("A cancelled invoice is rendered but marked cancelled")
    void cancelledInvoiceIsMarked() throws Exception {
        SalesInvoiceEntity inv = invoice("CANCELLED");
        inv.setCancellationReason("Customer cancelled before dispatch");

        String text = textOf(service.render(1L).content());

        assertTrue(text.contains("CANCELLED"));
        assertTrue(text.contains("Customer cancelled before dispatch"));
    }

    @Test
    @DisplayName("A reverse-charge invoice says so on its face")
    void reverseChargeIsShown() throws Exception {
        SalesInvoiceEntity inv = invoice("ISSUED");
        inv.setReverseCharge(true);

        assertTrue(textOf(service.render(1L).content()).contains("reverse charge"));
    }

    // ── Refusals ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A draft cannot be rendered as a tax invoice")
    void draftCannotBeRendered() {
        SalesInvoiceEntity inv = invoice("DRAFT");
        inv.setInvoiceNumber(null);

        VeloriaException e = assertThrows(VeloriaException.class, () -> service.render(1L));
        assertTrue(e.getMessage().contains("Issue the invoice first"));
    }

    @Test
    @DisplayName("Another organisation's invoice cannot be rendered")
    void crossTenantRenderIsRefused() {
        SalesInvoiceEntity inv = invoice("ISSUED");
        inv.setOrganizationId(999L);

        VeloriaException e = assertThrows(VeloriaException.class, () -> service.render(1L));
        assertTrue(e.getMessage().contains("another organisation"));
    }

    @Test
    @DisplayName("An unknown invoice is reported, not rendered blank")
    void unknownInvoiceIsReported() {
        when(invoiceRepo.findById(404L)).thenReturn(Optional.empty());

        assertThrows(VeloriaException.class, () -> service.render(404L));
    }
}
