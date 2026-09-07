package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.PurchaseInvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Vendor tax invoices — the inward side of the GST chain.
 *
 * The tax figures in the request body are the vendor's own, taken from their
 * invoice, and are recorded as stated because that is what credit may be claimed
 * on. They are validated for internal consistency before being accepted; nothing
 * here lets a caller invent credit that no vendor charged.
 */
@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/gst/purchase-invoices")
@RequiredArgsConstructor
public class PurchaseInvoiceController extends AppController {

    private final PurchaseInvoiceService purchaseInvoiceService;

    /** One line of the vendor's invoice. */
    public record LineRequest(String productName, String description, String hsnCode,
                              Integer quantity, String unit, Long unitPricePaise,
                              Long discountPaise, Integer gstRateBp,
                              Long cgstPaise, Long sgstPaise, Long igstPaise, Long cessPaise) {}

    public record RecordRequest(String vendorGstin, String vendorName,
                                String vendorInvoiceNumber, LocalDate vendorInvoiceDate,
                                String placeOfSupply, Boolean reverseCharge,
                                List<LineRequest> lines) {}

    @PreAuthorize("hasAuthority('CREATE_PURCHASE')")
    @PostMapping
    public ResponseEntity<Response> record(@RequestBody RecordRequest req) throws VeloriaException {
        List<PurchaseInvoiceService.Line> lines = req.lines() == null ? List.of()
                : req.lines().stream()
                    .map(l -> new PurchaseInvoiceService.Line(
                            l.productName(), l.description(), l.hsnCode(),
                            l.quantity() == null ? 0 : l.quantity(),
                            l.unit(),
                            nz(l.unitPricePaise()), nz(l.discountPaise()),
                            l.gstRateBp() == null ? 0 : l.gstRateBp(),
                            nz(l.cgstPaise()), nz(l.sgstPaise()),
                            nz(l.igstPaise()), nz(l.cessPaise())))
                    .toList();

        return data(ResponseCode.CREATED, "Purchase invoice recorded",
                purchaseInvoiceService.record(new PurchaseInvoiceService.VendorInvoice(
                        req.vendorGstin(), req.vendorName(), req.vendorInvoiceNumber(),
                        req.vendorInvoiceDate(), req.placeOfSupply(),
                        Boolean.TRUE.equals(req.reverseCharge()), lines)));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping
    public ResponseEntity<Response> forPeriod(@RequestParam String taxPeriod) {
        return data(ResponseCode.FETCHED, "Purchase invoices",
                purchaseInvoiceService.forPeriod(taxPeriod));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/{id}/lines")
    public ResponseEntity<Response> lines(@PathVariable Long id) {
        return data(ResponseCode.FETCHED, "Purchase invoice lines",
                purchaseInvoiceService.lines(id));
    }


    // ── From a purchase order (phase 11) ─────────────────────────────────────

    /** Purchase orders whose vendor GST has not yet become input credit. */
    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/awaiting")
    public ResponseEntity<Response> awaiting() {
        return data(ResponseCode.FETCHED, "Purchase orders awaiting a vendor invoice",
                purchaseInvoiceService.ordersAwaitingInvoice());
    }

    /**
     * Records the vendor invoice already captured against a purchase order,
     * turning the GST the vendor charged into claimable input credit.
     */
    @PreAuthorize("hasAuthority('CREATE_PURCHASE')")
    @PostMapping("/from-purchase-order/{purchaseOrderId}")
    public ResponseEntity<Response> fromPurchaseOrder(@PathVariable Long purchaseOrderId)
            throws VeloriaException {
        return data(ResponseCode.CREATED, "Purchase invoice recorded from the purchase order",
                purchaseInvoiceService.recordFromPurchaseOrder(purchaseOrderId));
    }

    private static long nz(Long v) { return v == null ? 0L : v; }
}
