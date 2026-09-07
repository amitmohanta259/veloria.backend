package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.entity.SalesInvoiceEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.SalesInvoiceRepository;
import com.app.master.service.service.admin.GstInvoiceNumberService;
import com.app.master.service.service.admin.SalesInvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The sales invoice — the GST document, distinct from the order.
 *
 * No monetary value is accepted from the caller. Amounts are recalculated on
 * the backend from the order snapshot and the tax rules in force on the
 * invoice date (spec phase 30).
 */
@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/gst/invoices")
@RequiredArgsConstructor
public class SalesInvoiceController extends AppController {

    private final SalesInvoiceService invoiceService;
    private final com.app.master.service.service.admin.GstInvoicePdfService invoicePdfService;
    private final SalesInvoiceRepository invoiceRepo;
    private final GstInvoiceNumberService numberService;
    private final com.app.master.service.core.security.GstSecurityContext securityContext;

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping
    public ResponseEntity<Response> list(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) throws VeloriaException {
        return data(ResponseCode.FETCHED, "Invoices fetched",
                invoiceRepo.findFiltered(securityContext.organizationId(), period, status, search,
                        PageRequest.of(page, size)));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/{id}")
    public ResponseEntity<Response> detail(@PathVariable Long id) throws VeloriaException {
        SalesInvoiceEntity inv = invoiceRepo.findById(id)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Invoice not found: " + id));
        if (!securityContext.organizationId().equals(inv.getOrganizationId())) {
            throw new VeloriaException(ResponseCode.NOT_FOUND, "Invoice not found: " + id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("invoice", inv);
        out.put("items", invoiceService.itemsOf(id));
        return data(ResponseCode.FETCHED, "Invoice fetched", out);
    }

    /** Builds a DRAFT invoice from an order. Idempotent per order. */
    @PreAuthorize("hasAuthority('CREATE_INVOICE')")
    @PostMapping("/order/{orderCode}/draft")
    public ResponseEntity<Response> draft(@PathVariable String orderCode) throws VeloriaException {
        return data(ResponseCode.CREATED, "Draft invoice created",
                invoiceService.createDraft(orderCode));
    }

    /** Allocates the invoice number and posts the ledger movements. */
    @PreAuthorize("hasAuthority('CREATE_INVOICE')")
    @PostMapping("/{id}/issue")
    public ResponseEntity<Response> issue(@PathVariable Long id) throws VeloriaException {
        return data(ResponseCode.UPDATED, "Invoice issued", invoiceService.issue(id));
    }

    /** Cancels an issued invoice, preserving the row, the number and the history. */
    @PreAuthorize("hasAuthority('CREATE_INVOICE')")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Response> cancel(@PathVariable Long id,
                                            @RequestBody Map<String, String> body) throws VeloriaException {
        return data(ResponseCode.UPDATED, "Invoice cancelled",
                invoiceService.cancel(id, body != null ? body.get("reason") : null));
    }

    /** Supersedes an issued invoice with a new one that references it. */
    @PreAuthorize("hasAuthority('CREATE_INVOICE')")
    @PostMapping("/{id}/amend")
    public ResponseEntity<Response> amend(@PathVariable Long id,
                                           @RequestBody Map<String, String> body) throws VeloriaException {
        return data(ResponseCode.UPDATED, "Invoice amended",
                invoiceService.amend(id, body != null ? body.get("reason") : null));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/series")
    public ResponseEntity<Response> series() throws VeloriaException {
        return data(ResponseCode.FETCHED, "Invoice series",
                numberService.list(securityContext.organizationId()));
    }

    // ── Invoice PDF (phase 19) ───────────────────────────────────────────────

    /**
     * The tax invoice as a PDF, rendered from the stored snapshot. Figures are
     * reproduced as issued and never recomputed against current rates.
     */
    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) throws VeloriaException {
        com.app.master.service.service.admin.GstInvoicePdfService.Rendered rendered = invoicePdfService.render(id);

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", rendered.fileName());
        return new ResponseEntity<>(rendered.content(), headers, org.springframework.http.HttpStatus.OK);
    }
}
