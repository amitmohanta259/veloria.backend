package com.app.master.service.service.admin;

import com.app.master.service.core.entity.GstRegistrationEntity;
import com.app.master.service.core.entity.SalesInvoiceEntity;
import com.app.master.service.core.entity.SalesInvoiceItemEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.security.GstSecurityContext;
import com.app.master.service.repository.admin.SalesInvoiceItemRepository;
import com.app.master.service.repository.admin.SalesInvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Renders a tax invoice from its stored snapshot.
 *
 * <b>Nothing here recalculates anything.</b> Every figure on the page is read
 * from the invoice and its lines exactly as they were written when the invoice
 * was issued. A rate change since then must not alter a document already given
 * to a customer, so this service never consults the rate rules — if a number is
 * wrong on the PDF it is wrong in the ledger too, which is the property that
 * makes the document trustworthy.
 *
 * A draft cannot be rendered: an invoice without a number is not a tax invoice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstInvoicePdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private static final float MARGIN = 40f;
    private static final float LEADING = 13f;

    private final SalesInvoiceRepository invoiceRepo;
    private final SalesInvoiceItemRepository itemRepo;
    private final GstIdentityService identityService;
    private final GstAuditService auditService;
    private final GstSecurityContext securityContext;

    private Long orgId() {
        return securityContext.current()
                .map(com.app.master.service.core.security.GstPrincipal::organizationId)
                .filter(Objects::nonNull)
                .orElseGet(identityService::defaultOrganizationId);
    }

    /** The rendered invoice, ready to download. */
    public record Rendered(String fileName, byte[] content) {}

    public Rendered render(Long invoiceId) throws VeloriaException {
        SalesInvoiceEntity inv = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Invoice " + invoiceId + " was not found"));

        if (!Objects.equals(inv.getOrganizationId(), orgId())) {
            throw new VeloriaException(ResponseCode.ACCESS_DENIED,
                    "This invoice belongs to another organisation");
        }
        if ("DRAFT".equals(inv.getStatus()) || inv.getInvoiceNumber() == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A draft has no invoice number, so it cannot be issued as a tax invoice. "
                    + "Issue the invoice first.");
        }

        List<SalesInvoiceItemEntity> lines =
                itemRepo.findBySalesInvoiceIdOrderByLineNumberAsc(invoiceId);

        byte[] pdf = build(inv, lines);

        auditService.log("SALES_INVOICE", inv.getId(), inv.getInvoiceNumber(),
                "INVOICE_PDF_RENDERED", inv.getTaxPeriod(), securityContext.actor());

        String safe = inv.getInvoiceNumber().replaceAll("[^A-Za-z0-9._-]", "-");
        return new Rendered(safe + ".pdf", pdf);
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private byte[] build(SalesInvoiceEntity inv, List<SalesInvoiceItemEntity> lines)
            throws VeloriaException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            var regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            float width = page.getMediaBox().getWidth();
            float y = page.getMediaBox().getHeight() - MARGIN;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {

                // ── Title ────────────────────────────────────────────────────
                y = text(cs, bold, 16, MARGIN, y, "TAX INVOICE");
                if (inv.getReverseCharge() != null && inv.getReverseCharge()) {
                    y = text(cs, bold, 9, MARGIN, y, "Payable under reverse charge");
                }
                if ("CANCELLED".equals(inv.getStatus())) {
                    y = text(cs, bold, 11, MARGIN, y, "CANCELLED — " + nvl(inv.getCancellationReason()));
                } else if ("AMENDED".equals(inv.getStatus())) {
                    y = text(cs, bold, 11, MARGIN, y, "AMENDED — superseded by a later invoice");
                }
                y -= 6;

                // ── Supplier ─────────────────────────────────────────────────
                GstRegistrationEntity reg = identityService.primaryRegistration(orgId()).orElse(null);
                y = text(cs, bold, 10, MARGIN, y, "Supplier");
                y = text(cs, regular, 9, MARGIN, y, reg != null ? nvl(reg.getLegalName()) : "Veloria");
                if (reg != null && reg.getAddress() != null) {
                    y = text(cs, regular, 9, MARGIN, y, reg.getAddress());
                }
                y = text(cs, regular, 9, MARGIN, y, "GSTIN: " + nvl(inv.getSellerGstin())
                        + "    State: " + nvl(inv.getSellerStateCode()));
                y -= 6;

                // ── Invoice identity ─────────────────────────────────────────
                y = text(cs, regular, 9, MARGIN, y,
                        "Invoice number: " + inv.getInvoiceNumber()
                        + "    Date: " + (inv.getInvoiceDate() == null ? "—" : DATE.format(inv.getInvoiceDate())));
                y = text(cs, regular, 9, MARGIN, y,
                        "Place of supply: " + nvl(inv.getPlaceOfSupply())
                        + "    Supply type: " + nvl(inv.getSupplyType())
                        + "    Period: " + nvl(inv.getTaxPeriod()));
                y -= 6;

                // ── Customer ─────────────────────────────────────────────────
                y = text(cs, bold, 10, MARGIN, y, "Billed to");
                y = text(cs, regular, 9, MARGIN, y, nvl(inv.getCustomerLegalName() != null
                        ? inv.getCustomerLegalName() : inv.getCustomerName()));
                if (inv.getBillingAddressSnapshot() != null) {
                    y = text(cs, regular, 9, MARGIN, y, inv.getBillingAddressSnapshot());
                }
                if (inv.getCustomerGstin() != null && !inv.getCustomerGstin().isBlank()) {
                    y = text(cs, regular, 9, MARGIN, y, "GSTIN: " + inv.getCustomerGstin());
                }
                if (inv.getShippingAddressSnapshot() != null
                        && !inv.getShippingAddressSnapshot().equals(inv.getBillingAddressSnapshot())) {
                    y = text(cs, bold, 10, MARGIN, y - 4, "Shipped to");
                    y = text(cs, regular, 9, MARGIN, y, inv.getShippingAddressSnapshot());
                }
                y -= 10;

                // ── Lines ────────────────────────────────────────────────────
                float[] col = {MARGIN, MARGIN + 150, MARGIN + 200, MARGIN + 235,
                               MARGIN + 300, MARGIN + 360, MARGIN + 420, width - MARGIN - 70};
                y = row(cs, bold, 8, y, col,
                        "Description", "HSN", "Qty", "Unit price", "Taxable", "Rate", "Tax", "Total");
                y -= 2;

                for (SalesInvoiceItemEntity l : lines) {
                    y = row(cs, regular, 8, y, col,
                            truncate(nvl(l.getProductName()), 34),
                            nvl(l.getHsnCode()),
                            String.valueOf(l.getQuantity()),
                            rupees(l.getUnitPrice()),
                            rupees(l.getTaxableValue()),
                            bp(l.getGstRateBp()),
                            rupees(l.getTotalTax()),
                            rupees(l.getTotalValue()));
                    if (y < 150) break;   // one page; the ledger remains the full record
                }

                y -= 10;

                // ── Totals ───────────────────────────────────────────────────
                float labelX = width - MARGIN - 220;
                float valueX = width - MARGIN - 80;
                y = totalRow(cs, regular, y, labelX, valueX, "Gross value", rupees(inv.getGrossValue()));
                if (nz(inv.getDiscountValue()) != 0) {
                    y = totalRow(cs, regular, y, labelX, valueX, "Discount", rupees(inv.getDiscountValue()));
                }
                if (nz(inv.getShippingValue()) != 0) {
                    y = totalRow(cs, regular, y, labelX, valueX, "Shipping", rupees(inv.getShippingValue()));
                }
                y = totalRow(cs, regular, y, labelX, valueX, "Taxable value", rupees(inv.getTaxableValue()));

                // Only the heads that actually apply are printed, so an
                // intra-state invoice never shows an empty IGST line.
                if (nz(inv.getCgstAmount()) != 0) {
                    y = totalRow(cs, regular, y, labelX, valueX, "CGST", rupees(inv.getCgstAmount()));
                }
                if (nz(inv.getSgstAmount()) != 0) {
                    y = totalRow(cs, regular, y, labelX, valueX, "SGST", rupees(inv.getSgstAmount()));
                }
                if (nz(inv.getIgstAmount()) != 0) {
                    y = totalRow(cs, regular, y, labelX, valueX, "IGST", rupees(inv.getIgstAmount()));
                }
                if (nz(inv.getCessAmount()) != 0) {
                    y = totalRow(cs, regular, y, labelX, valueX, "Cess", rupees(inv.getCessAmount()));
                }
                y = totalRow(cs, regular, y, labelX, valueX, "Total tax", rupees(inv.getTotalTax()));
                if (nz(inv.getRoundOff()) != 0) {
                    y = totalRow(cs, regular, y, labelX, valueX, "Round off", rupees(inv.getRoundOff()));
                }
                y = totalRow(cs, bold, y, labelX, valueX, "Invoice total",
                        rupees(inv.getTotalInvoiceValue()));

                // ── Footer ───────────────────────────────────────────────────
                text(cs, regular, 7, MARGIN, 60f,
                        "Figures are reproduced from the invoice as issued and are not recomputed.");
            }

            doc.save(out);
            return out.toByteArray();

        } catch (Exception e) {
            log.error("Could not render invoice {}", inv.getInvoiceNumber(), e);
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Could not render the invoice PDF: " + e.getMessage());
        }
    }

    // ── Drawing helpers ──────────────────────────────────────────────────────

    private float text(PDPageContentStream cs, PDType1Font font, float size,
                       float x, float y, String s) throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitise(s));
        cs.endText();
        return y - LEADING;
    }

    private float row(PDPageContentStream cs, PDType1Font font, float size, float y,
                      float[] col, String... cells) throws Exception {
        for (int i = 0; i < cells.length && i < col.length; i++) {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(col[i], y);
            cs.showText(sanitise(cells[i]));
            cs.endText();
        }
        return y - LEADING;
    }

    private float totalRow(PDPageContentStream cs, PDType1Font font, float y,
                           float labelX, float valueX, String label, String value) throws Exception {
        cs.beginText();
        cs.setFont(font, 9);
        cs.newLineAtOffset(labelX, y);
        cs.showText(sanitise(label));
        cs.endText();
        cs.beginText();
        cs.setFont(font, 9);
        cs.newLineAtOffset(valueX, y);
        cs.showText(sanitise(value));
        cs.endText();
        return y - LEADING;
    }

    /** Helvetica is WinAnsi; anything outside it would throw mid-render. */
    private String sanitise(String s) {
        if (s == null) return "";
        return s.replace('₹', 'R').replaceAll("[^\\x20-\\x7E]", " ").trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String rupees(Long paise) {
        long p = nz(paise);
        return String.format("%,.2f", p / 100.0);
    }

    private static String bp(Integer rateBp) {
        return rateBp == null ? "" : (rateBp / 100.0) + "%";
    }

    private static long nz(Long v) { return v == null ? 0L : v; }

    private static String nvl(String s) { return s == null ? "" : s; }
}
