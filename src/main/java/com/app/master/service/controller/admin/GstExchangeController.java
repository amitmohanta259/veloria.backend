package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.GstComplianceDocumentService;
import com.app.master.service.service.admin.OrderExchangeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exchange and replacement, plus the compliance-document endpoints
 * (spec phases 14, 15, 23, 24, 25).
 *
 * No monetary value is accepted from the caller: replacement prices come from
 * the product master and all tax is computed on the backend.
 */
@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/gst")
@RequiredArgsConstructor
public class GstExchangeController extends AppController {

    private final OrderExchangeService exchangeService;
    private final GstComplianceDocumentService complianceService;

    // ── Exchange / replacement ───────────────────────────────────────────────

    @PreAuthorize("hasAuthority('CREATE_CREDIT_NOTE')")
    @PostMapping("/exchanges/order/{orderCode}")
    public ResponseEntity<Response> exchange(@PathVariable String orderCode,
                                              @RequestBody ExchangeRequest body) throws VeloriaException {
        List<OrderExchangeService.ReturnLine> returns = body.getReturnItems() == null ? List.of()
                : body.getReturnItems().stream()
                    .map(i -> new OrderExchangeService.ReturnLine(
                            i.getOrderItemId(), i.getQuantity(), i.getCondition()))
                    .toList();
        List<OrderExchangeService.ReplacementLine> replacements = body.getReplacementItems() == null ? List.of()
                : body.getReplacementItems().stream()
                    .map(i -> new OrderExchangeService.ReplacementLine(
                            i.getProductUuid(), i.getQuantity(), i.getUnitPricePaise()))
                    .toList();

        return data(ResponseCode.CREATED, "Exchange recorded",
                exchangeService.exchange(orderCode, returns, replacements,
                        body.getExchangeType(), body.getReason()));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/exchanges")
    public ResponseEntity<Response> exchanges() {
        return data(ResponseCode.FETCHED, "Exchanges", exchangeService.list());
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/exchanges/{id}/items")
    public ResponseEntity<Response> exchangeItems(@PathVariable Long id) {
        return data(ResponseCode.FETCHED, "Exchange items", exchangeService.itemsOf(id));
    }

    // ── e-Invoice (spec phase 23) ────────────────────────────────────────────

    @PreAuthorize("hasAuthority('CREATE_INVOICE')")
    @PostMapping("/e-invoice/{salesInvoiceId}/assess")
    public ResponseEntity<Response> assessEInvoice(@PathVariable Long salesInvoiceId)
            throws VeloriaException {
        return data(ResponseCode.OK, "e-Invoice applicability assessed",
                complianceService.assessEInvoice(salesInvoiceId));
    }

    /**
     * Records an IRN received from the IRP. Both the IRN and the
     * acknowledgement number are required — neither is generated here.
     */
    @PreAuthorize("hasAuthority('ADMIN_GST')")
    @PostMapping("/e-invoice/{documentId}/record-irn")
    public ResponseEntity<Response> recordIrn(@PathVariable Long documentId,
                                               @RequestBody Map<String, String> body)
            throws VeloriaException {
        return data(ResponseCode.UPDATED, "IRN recorded",
                complianceService.recordIrn(documentId, body.get("irn"),
                        body.get("acknowledgementNumber"), body.get("signedQrPayload"),
                        body.get("provider")));
    }

    // ── e-Way bill (spec phase 24) ───────────────────────────────────────────

    @PreAuthorize("hasAuthority('CREATE_INVOICE')")
    @PostMapping("/e-waybill/{salesInvoiceId}/assess")
    public ResponseEntity<Response> assessEWayBill(@PathVariable Long salesInvoiceId)
            throws VeloriaException {
        return data(ResponseCode.OK, "e-Way bill applicability assessed",
                complianceService.assessEWayBill(salesInvoiceId));
    }

    @PreAuthorize("hasAuthority('ADMIN_GST')")
    @PostMapping("/e-waybill/{documentId}/record-number")
    public ResponseEntity<Response> recordEwb(@PathVariable Long documentId,
                                               @RequestBody Map<String, String> body)
            throws VeloriaException {
        return data(ResponseCode.UPDATED, "e-Way bill number recorded",
                complianceService.recordEwbNumber(documentId, body.get("ewbNumber"),
                        body.get("provider")));
    }

    // ── GSTIN verification (spec phase 25) ───────────────────────────────────

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @PostMapping("/gstin/verify")
    public ResponseEntity<Response> verifyGstin(@RequestBody Map<String, String> body)
            throws VeloriaException {
        return data(ResponseCode.OK, "GSTIN checked",
                complianceService.verifyGstin(body.get("gstin")));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/gstin/verifications")
    public ResponseEntity<Response> verifications() throws VeloriaException {
        return data(ResponseCode.FETCHED, "GSTIN checks", complianceService.verifications());
    }

    // ── Request bodies ───────────────────────────────────────────────────────

    @lombok.Data
    public static class ExchangeRequest {
        private List<ReturnItem> returnItems;
        private List<ReplacementItem> replacementItems;
        /** EXCHANGE or REPLACEMENT */
        private String exchangeType;
        private String reason;
    }

    @lombok.Data
    public static class ReturnItem {
        private Long orderItemId;
        private Integer quantity;
        private String condition;
    }

    @lombok.Data
    public static class ReplacementItem {
        private UUID productUuid;
        private Integer quantity;
        /** Optional override; defaults to the product master price. */
        private Long unitPricePaise;
    }
}
