package com.app.master.service.service.admin;

import com.app.master.service.core.entity.GstInputTaxEntity;
import com.app.master.service.core.entity.GstRegistrationEntity;
import com.app.master.service.core.entity.PurchaseInvoiceEntity;
import com.app.master.service.core.entity.PurchaseInvoiceItemEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.security.GstSecurityContext;
import com.app.master.service.repository.admin.GstInputTaxRepository;
import com.app.master.service.repository.admin.PurchaseInvoiceItemRepository;
import com.app.master.service.repository.admin.PurchaseInvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Records a vendor's tax invoice, and turns it into input GST the ITC engine
 * can work from.
 *
 * This is the inward mirror of {@link SalesInvoiceService} and completes the
 * chain the GST return depends on:
 *
 * <pre>purchase → purchase invoice → input GST → ITC → GSTR-3B</pre>
 *
 * <b>The tax is the vendor's, not ours.</b> On a sale we compute the tax because
 * we are the supplier. On a purchase the supplier already decided it, and the
 * amount we may claim as credit is the amount they actually charged — so the
 * vendor's stated figures are recorded as given. They are not, however, trusted
 * blindly: each invoice is checked for internal contradictions, and one that
 * does not hold together is refused rather than recorded and later claimed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseInvoiceService {

    private final com.app.master.service.repository.admin.SupplierRepository supplierRepo;
    private final com.app.master.service.repository.admin.PurchaseOrderRepository purchaseOrderRepo;
    private final com.app.master.service.repository.admin.PurchaseOrderItemRepository purchaseOrderItemRepo;
    private final PurchaseInvoiceRepository invoiceRepo;
    private final PurchaseInvoiceItemRepository itemRepo;
    private final GstInputTaxRepository inputTaxRepo;
    private final GstMovementService movementService;
    private final GstIdentityService identityService;
    private final GstTaxPeriodService periodService;
    private final GstRoundingService rounding;
    private final GstAuditService auditService;
    private final GstSecurityContext securityContext;

    /** One line of a vendor invoice, exactly as the vendor stated it. */
    public record Line(String productName, String description, String hsnCode,
                       int quantity, String unit, long unitPricePaise, long discountPaise,
                       int gstRateBp, long cgstPaise, long sgstPaise, long igstPaise,
                       long cessPaise) {}

    /** The header of a vendor invoice. */
    public record VendorInvoice(String vendorGstin, String vendorName, String vendorInvoiceNumber,
                                LocalDate vendorInvoiceDate, String placeOfSupply,
                                boolean reverseCharge, List<Line> lines) {}

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

    // ── Record ───────────────────────────────────────────────────────────────

    /**
     * Records a vendor invoice, its lines, and the input GST it carries, then
     * posts the inward ledger movement.
     *
     * Everything happens in one transaction: an invoice that fails validation
     * part-way must leave nothing behind, or a later ITC claim could be made
     * against a half-written purchase.
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseInvoiceEntity record(VendorInvoice vendorInvoice) throws VeloriaException {
        validateHeader(vendorInvoice);

        String taxPeriod = YearMonth.from(vendorInvoice.vendorInvoiceDate()).toString();
        periodService.assertOpen(taxPeriod);

        // A vendor invoice number is unique per vendor. Recording it twice would
        // double the credit available, so the second attempt is refused.
        var duplicate = invoiceRepo.findByOrganizationIdAndVendorGstinAndVendorInvoiceNumber(
                orgId(), vendorInvoice.vendorGstin(), vendorInvoice.vendorInvoiceNumber());
        if (duplicate.isPresent()) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "Vendor invoice " + vendorInvoice.vendorInvoiceNumber() + " from "
                    + identityService.maskGstin(vendorInvoice.vendorGstin())
                    + " is already recorded as purchase invoice " + duplicate.get().getId()
                    + ". Recording it again would double the credit available.");
        }

        String vendorState = identityService.stateCodeOf(vendorInvoice.vendorGstin());
        String placeOfSupply = vendorInvoice.placeOfSupply() != null
                ? vendorInvoice.placeOfSupply()
                : ourStateCode();
        String supplyType = identityService.supplyType(placeOfSupply, vendorState);
        boolean interState = identityService.isInterState(placeOfSupply, vendorState);

        Totals t = new Totals();
        List<PurchaseInvoiceItemEntity> lines = new ArrayList<>();
        int lineNumber = 1;
        for (Line l : vendorInvoice.lines()) {
            lines.add(buildLine(l, lineNumber++, interState, t));
        }

        PurchaseInvoiceEntity invoice = invoiceRepo.save(PurchaseInvoiceEntity.builder()
                .organizationId(orgId())
                .gstRegistrationId(regId())
                .vendorGstin(vendorInvoice.vendorGstin())
                .vendorName(vendorInvoice.vendorName())
                .vendorStateCode(vendorState)
                .vendorInvoiceNumber(vendorInvoice.vendorInvoiceNumber())
                .vendorInvoiceDate(vendorInvoice.vendorInvoiceDate())
                .invoiceType("B2B")
                .placeOfSupply(placeOfSupply)
                .supplyType(supplyType)
                .reverseCharge(vendorInvoice.reverseCharge())
                .grossValue(t.gross)
                .discountValue(t.discount)
                .taxableValue(t.taxable)
                .cgstAmount(t.cgst).sgstAmount(t.sgst).igstAmount(t.igst).cessAmount(t.cess)
                .totalTax(t.totalTax())
                .totalInvoiceValue(t.taxable + t.totalTax())
                .status("RECORDED")
                .taxPeriod(taxPeriod)
                .financialYear(GstInvoiceNumberService.financialYear(vendorInvoice.vendorInvoiceDate()))
                .createdBy(securityContext.actor())
                .build());

        for (PurchaseInvoiceItemEntity line : lines) line.setPurchaseInvoiceId(invoice.getId());
        itemRepo.saveAll(lines);

        // The input-tax record is what the ITC engine works from. It is created
        // here rather than by the caller so a purchase invoice can never exist
        // without the credit record that belongs to it.
        GstInputTaxEntity inputTax = inputTaxRepo.save(GstInputTaxEntity.builder()
                .vendorGstin(vendorInvoice.vendorGstin())
                .vendorName(vendorInvoice.vendorName())
                .vendorStateCode(vendorState)
                .invoiceNumber(vendorInvoice.vendorInvoiceNumber())
                .invoiceDate(vendorInvoice.vendorInvoiceDate())
                .taxPeriod(taxPeriod)
                .financialYear(invoice.getFinancialYear())
                .taxableValue(t.taxable)
                .cgstAmount(t.cgst).sgstAmount(t.sgst).igstAmount(t.igst)
                .totalInputTax(t.totalTax())
                .itcStatus("PENDING_REVIEW")
                .organizationId(orgId())
                .gstRegistrationId(regId())
                .build());

        invoice.setInputTaxId(inputTax.getId());
        invoiceRepo.save(invoice);

        // Reuses the existing inward posting — this service does not write to
        // the ledger itself, so there remains exactly one way a purchase
        // reaches it.
        movementService.recordPurchaseMovement(inputTax);

        auditService.log("PURCHASE_INVOICE", invoice.getId(), vendorInvoice.vendorInvoiceNumber(),
                "PURCHASE_INVOICE_RECORDED", taxPeriod, securityContext.actor());

        log.info("Recorded purchase invoice {} from {} — {} line(s), {} paise input tax",
                vendorInvoice.vendorInvoiceNumber(),
                identityService.maskGstin(vendorInvoice.vendorGstin()),
                lines.size(), t.totalTax());

        return invoice;
    }

    // ── Line ─────────────────────────────────────────────────────────────────

    private PurchaseInvoiceItemEntity buildLine(Line l, int lineNumber,
                                                boolean interState, Totals t)
            throws VeloriaException {
        String where = "line " + lineNumber;

        if (l.quantity() <= 0) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    where + " has quantity " + l.quantity() + "; a purchase line needs at least one unit");
        }
        long gross = rounding.taxableValue(l.unitPricePaise(), l.quantity());
        long discount = Math.max(0, l.discountPaise());
        if (discount > gross) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    where + " has a discount larger than the line value");
        }
        long taxable = gross - discount;

        // The vendor cannot charge both an inter-state and an intra-state head
        // on the same supply. Recording one that does would create credit that
        // reconciliation could never match against GSTR-2B.
        if (interState && (l.cgstPaise() != 0 || l.sgstPaise() != 0)) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    where + " is an inter-state supply but carries CGST/SGST. "
                    + "Check the vendor's place of supply before recording it.");
        }
        if (!interState && l.igstPaise() != 0) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    where + " is an intra-state supply but carries IGST. "
                    + "Check the vendor's place of supply before recording it.");
        }
        if (l.cgstPaise() < 0 || l.sgstPaise() < 0 || l.igstPaise() < 0 || l.cessPaise() < 0) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    where + " has a negative tax amount");
        }

        long lineTax = l.cgstPaise() + l.sgstPaise() + l.igstPaise() + l.cessPaise();

        t.gross += gross;
        t.discount += discount;
        t.taxable += taxable;
        t.cgst += l.cgstPaise();
        t.sgst += l.sgstPaise();
        t.igst += l.igstPaise();
        t.cess += l.cessPaise();

        return PurchaseInvoiceItemEntity.builder()
                .lineNumber(lineNumber)
                .productName(l.productName())
                .description(l.description())
                .hsnCode(l.hsnCode())
                .quantity(l.quantity())
                .unit(l.unit() == null ? "PCS" : l.unit())
                .unitPrice(l.unitPricePaise())
                .grossValue(gross)
                .discount(discount)
                .taxableValue(taxable)
                .gstRateBp(l.gstRateBp())
                .cgstRateBp(interState ? 0 : l.gstRateBp() / 2)
                .cgstAmount(l.cgstPaise())
                .sgstRateBp(interState ? 0 : l.gstRateBp() / 2)
                .sgstAmount(l.sgstPaise())
                .igstRateBp(interState ? l.gstRateBp() : 0)
                .igstAmount(l.igstPaise())
                .cessAmount(l.cessPaise())
                .totalTax(lineTax)
                .totalValue(taxable + lineTax)
                .build();
    }

    // ── Validation ───────────────────────────────────────────────────────────

    private void validateHeader(VendorInvoice v) throws VeloriaException {
        if (v == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "No vendor invoice was supplied");
        }
        if (v.vendorInvoiceNumber() == null || v.vendorInvoiceNumber().isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A vendor invoice number is required — it is what GSTR-2B is matched on");
        }
        if (v.vendorInvoiceDate() == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A vendor invoice date is required — it determines the tax period");
        }
        // Without a valid vendor GSTIN there is no credit to claim, so this is
        // refused at entry rather than surfacing later as an unmatchable record.
        if (v.vendorGstin() == null || v.vendorGstin().isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A vendor GSTIN is required before input tax credit can be claimed");
        }
        if (!identityService.isValidGstin(v.vendorGstin())) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Vendor GSTIN " + identityService.maskGstin(v.vendorGstin())
                    + " is not structurally valid, so credit against it cannot be claimed");
        }
        if (v.lines() == null || v.lines().isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A purchase invoice needs at least one line");
        }
    }

    /** Our own state — the place of supply for goods we receive. */
    private String ourStateCode() throws VeloriaException {
        return identityService.primaryRegistration(orgId())
                .map(GstRegistrationEntity::getGstin)
                .map(identityService::stateCodeOf)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST,
                        "No GST registration is configured, so the place of supply for a purchase "
                        + "cannot be determined"));
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    public List<PurchaseInvoiceEntity> forPeriod(String taxPeriod) {
        return invoiceRepo.findByOrganizationIdAndTaxPeriodOrderByIdAsc(orgId(), taxPeriod);
    }

    public List<PurchaseInvoiceItemEntity> lines(Long purchaseInvoiceId) {
        return itemRepo.findByPurchaseInvoiceIdOrderByLineNumberAsc(purchaseInvoiceId);
    }


    // ── From a purchase order (phase 11) ─────────────────────────────────────

    /**
     * Records the vendor invoice already captured against a purchase order.
     *
     * The purchase order header holds what was read off the vendor's invoice
     * PDF — their GSTIN, the invoice number, and the tax they charged. That is
     * genuine vendor data, so it is carried through rather than re-derived: the
     * tax recorded here is the tax the vendor actually charged, not a rate this
     * system assumed.
     *
     * The header tax is apportioned across the order's lines in proportion to
     * line value, with the last line absorbing the rounding remainder, so the
     * line amounts sum back to the vendor's stated total exactly.
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseInvoiceEntity recordFromPurchaseOrder(Long purchaseOrderId)
            throws VeloriaException {
        var po = purchaseOrderRepo.findById(purchaseOrderId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Purchase order " + purchaseOrderId + " was not found"));

        if (po.getVendorGstin() == null || po.getVendorGstin().isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Purchase order " + po.getPoCode() + " has no vendor GSTIN. Upload the vendor's "
                    + "tax invoice, or enter their GSTIN, before input credit can be claimed.");
        }
        if (po.getVendorInvoiceNumber() == null || po.getVendorInvoiceNumber().isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Purchase order " + po.getPoCode() + " has no vendor invoice number. "
                    + "GSTR-2B is matched on it, so it is required.");
        }

        long headerTax = nz(po.getCgstAmount()) + nz(po.getSgstAmount()) + nz(po.getIgstAmount());
        if (headerTax == 0) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "No GST has been captured against purchase order " + po.getPoCode()
                    + " (extraction status " + po.getGstExtractionStatus() + "). There is nothing "
                    + "to claim until the vendor's invoice is recorded.");
        }

        List<com.app.master.service.core.entity.PurchaseOrderItemEntity> poItems =
                purchaseOrderItemRepo.findByPurchaseOrderIdOrderByIdAsc(purchaseOrderId);
        if (poItems.isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Purchase order " + po.getPoCode() + " has no line items");
        }

        long lineValueTotal = poItems.stream().mapToLong(i -> nz(i.getLineTotal())).sum();
        if (lineValueTotal <= 0) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Purchase order " + po.getPoCode() + " has no line value to apportion tax across");
        }

        // The vendor's taxable value and the order's line values can legitimately
        // differ — a part shipment, a negotiated price. It is recorded and
        // surfaced rather than reconciled silently, because quietly adjusting one
        // to match the other would misstate the credit.
        long vendorTaxable = nz(po.getTaxableAmount());
        if (vendorTaxable > 0 && vendorTaxable != lineValueTotal) {
            log.warn("Purchase order {}: vendor invoice taxable {} paise differs from order line "
                     + "value {} paise — the vendor's figure is used for the credit",
                     po.getPoCode(), vendorTaxable, lineValueTotal);
        }

        boolean interState = nz(po.getIgstAmount()) > 0;
        long taxableBase = vendorTaxable > 0 ? vendorTaxable : lineValueTotal;

        List<Line> lines = new ArrayList<>();
        long cgstAllocated = 0, sgstAllocated = 0, igstAllocated = 0, taxableAllocated = 0;
        for (int i = 0; i < poItems.size(); i++) {
            var item = poItems.get(i);
            boolean last = i == poItems.size() - 1;
            long lineValue = nz(item.getLineTotal());

            long taxable = last ? taxableBase - taxableAllocated
                                : rounding.proportionalShare(taxableBase, lineValue, lineValueTotal);
            long cgst = last ? nz(po.getCgstAmount()) - cgstAllocated
                             : rounding.proportionalShare(nz(po.getCgstAmount()), lineValue, lineValueTotal);
            long sgst = last ? nz(po.getSgstAmount()) - sgstAllocated
                             : rounding.proportionalShare(nz(po.getSgstAmount()), lineValue, lineValueTotal);
            long igst = last ? nz(po.getIgstAmount()) - igstAllocated
                             : rounding.proportionalShare(nz(po.getIgstAmount()), lineValue, lineValueTotal);

            taxableAllocated += taxable; cgstAllocated += cgst;
            sgstAllocated += sgst; igstAllocated += igst;

            int qty = item.getQuantity() == null || item.getQuantity() <= 0 ? 1 : item.getQuantity();
            // Rounded up so unitPrice * qty is never below the vendor's taxable
            // value: the difference becomes the line discount, and the taxable
            // value recomputed downstream lands exactly on what the vendor charged.
            long unitPrice = (taxable + qty - 1) / qty;
            lines.add(new Line(item.getProductName(), item.getSkuId(), item.getHsnCode(),
                    qty, "PCS", unitPrice, (unitPrice * qty) - taxable,
                    rateBpFor(taxable, cgst + sgst + igst),
                    cgst, sgst, igst, 0L));
        }

        String vendorName = po.getSupplierUuid() == null ? null
                : supplierRepo.findByUuid(po.getSupplierUuid())
                        .map(com.app.master.service.core.entity.SupplierEntity::getName)
                        .orElse(null);

        VendorInvoice vendorInvoice = new VendorInvoice(
                po.getVendorGstin(), vendorName, po.getVendorInvoiceNumber(),
                po.getVendorInvoiceDate() != null ? po.getVendorInvoiceDate() : LocalDate.now(),
                interState ? null : ourStateCode(), false, lines);

        PurchaseInvoiceEntity recorded = record(vendorInvoice);
        recorded.setPurchaseOrderId(purchaseOrderId);
        invoiceRepo.save(recorded);

        auditService.log("PURCHASE_INVOICE", recorded.getId(), po.getPoCode(),
                "PURCHASE_INVOICE_FROM_ORDER", recorded.getTaxPeriod(), securityContext.actor());

        log.info("Recorded purchase invoice {} from purchase order {} — {} paise input tax",
                recorded.getVendorInvoiceNumber(), po.getPoCode(), recorded.getTotalTax());
        return recorded;
    }

    /** Effective rate in basis points, for the record. Zero when there is no base. */
    private int rateBpFor(long taxable, long tax) {
        if (taxable <= 0 || tax <= 0) return 0;
        return (int) Math.round((tax * 10000.0) / taxable);
    }

    /** Purchase orders carrying vendor GST that has not yet become input credit. */
    public List<com.app.master.service.core.entity.PurchaseOrderEntity> ordersAwaitingInvoice() {
        return purchaseOrderRepo.findByArchiveFalseOrderByCreatedDesc().stream()
                .filter(po -> po.getVendorGstin() != null && !po.getVendorGstin().isBlank())
                .filter(po -> nz(po.getCgstAmount()) + nz(po.getSgstAmount()) + nz(po.getIgstAmount()) > 0)
                .filter(po -> invoiceRepo.findByPurchaseOrderIdOrderByIdAsc(po.getId()).isEmpty())
                .toList();
    }

    private static long nz(Long v) { return v == null ? 0L : v; }

    /** Running totals while lines are built. */
    private static final class Totals {
        long gross, discount, taxable, cgst, sgst, igst, cess;
        long totalTax() { return cgst + sgst + igst + cess; }
    }
}
