package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.entity.GstCreditNoteEntity;
import com.app.master.service.core.entity.GstMovementLedgerEntity;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.GstAccountingExceptionRepository;
import com.app.master.service.repository.admin.GstAuditLogRepository;
import com.app.master.service.service.admin.GstCreditNoteService;
import com.app.master.service.service.admin.GstMovementService;
import com.app.master.service.service.admin.GstReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/gst/movements")
@RequiredArgsConstructor
public class GstMovementController extends AppController {

    private final GstMovementService gstMovementService;
    private final GstCreditNoteService creditNoteService;
    private final GstReconciliationService reconciliationService;
    private final GstAccountingExceptionRepository exceptionRepo;
    private final GstAuditLogRepository auditRepo;

    /** Dashboard summary for a period (or all if period is omitted). */
    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/summary")
    public ResponseEntity<Response> summary(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "GST movement summary",
                gstMovementService.getSummary(period));
    }

    /** Paged list of all movements with optional filters. */
    @PreAuthorize("hasAuthority('VIEW_GST_LEDGER')")
    @GetMapping
    public ResponseEntity<Response> list(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String movementType,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean includeHistorical,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Page<GstMovementLedgerEntity> result = gstMovementService.getMovements(
                period, movementType, direction, search, includeHistorical, page, size);
        return data(ResponseCode.FETCHED, "Movements fetched", result);
    }

    /** Paged credit notes. */
    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/credit-notes")
    public ResponseEntity<Response> creditNotes(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        Page<GstCreditNoteEntity> result =
                gstMovementService.getCreditNotes(period, search, page, size);
        return data(ResponseCode.FETCHED, "Credit notes fetched", result);
    }

    /**
     * Admin-triggered idempotent backfill.
     * POST /api/master/gst/movements/backfill
     * Migrates all existing gst_output_tax and gst_input_tax rows into the movement ledger.
     */
    @PreAuthorize("hasAuthority('ADMIN_GST')")
    @PostMapping("/backfill")
    public ResponseEntity<Response> backfill() {
        int out = gstMovementService.backfillOutputTax();
        int in  = gstMovementService.backfillInputTax();
        return data(ResponseCode.OK, "Backfill complete",
                Map.of("outputMovementsCreated", out, "inputMovementsCreated", in));
    }

    // ── Credit note lifecycle (spec section 16) ──────────────────────────────

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/credit-notes/{id}/items")
    public ResponseEntity<Response> creditNoteItems(@PathVariable Long id) {
        return data(ResponseCode.FETCHED, "Credit note items fetched",
                creditNoteService.itemsOf(id));
    }

    @PreAuthorize("hasAuthority('APPROVE_ITC')")
    @PostMapping("/credit-notes/{id}/approve")
    public ResponseEntity<Response> approveCreditNote(
            @PathVariable Long id, @RequestBody(required = false) Map<String, String> body)
            throws com.app.master.service.core.exception.VeloriaException {
        String by = body != null ? body.getOrDefault("approvedBy", "ADMIN") : "ADMIN";
        return data(ResponseCode.UPDATED, "Credit note approved",
                creditNoteService.approve(id, by));
    }

    @PreAuthorize("hasAuthority('CREATE_CREDIT_NOTE')")
    @PostMapping("/credit-notes/{id}/issue")
    public ResponseEntity<Response> issueCreditNote(
            @PathVariable Long id, @RequestBody(required = false) Map<String, String> body)
            throws com.app.master.service.core.exception.VeloriaException {
        String by = body != null ? body.getOrDefault("issuedBy", "ADMIN") : "ADMIN";
        return data(ResponseCode.UPDATED, "Credit note issued",
                creditNoteService.issue(id, by));
    }

    @PreAuthorize("hasAuthority('CREATE_CREDIT_NOTE')")
    @PostMapping("/credit-notes/{id}/cancel")
    public ResponseEntity<Response> cancelCreditNote(
            @PathVariable Long id, @RequestBody(required = false) Map<String, String> body)
            throws com.app.master.service.core.exception.VeloriaException {
        String reason = body != null ? body.getOrDefault("reason", "Cancelled by admin") : "Cancelled by admin";
        String by = body != null ? body.getOrDefault("cancelledBy", "ADMIN") : "ADMIN";
        return data(ResponseCode.UPDATED, "Credit note cancelled",
                creditNoteService.cancel(id, reason, by));
    }

    // ── Reconciliation and exceptions (spec sections 49 and 57) ──────────────

    /** Read-only sweep for GST records the data cannot account for. */
    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/reconciliation")
    public ResponseEntity<Response> reconciliation() {
        return data(ResponseCode.FETCHED, "Reconciliation report", reconciliationService.report());
    }

    /**
     * Rebuilds return lines for returns verified before item-level tracking.
     * Produces reviewable records only; nothing is issued automatically.
     */
    @PreAuthorize("hasAuthority('ADMIN_GST')")
    @PostMapping("/reconciliation/historical-returns")
    public ResponseEntity<Response> reconcileHistoricalReturns() {
        return data(ResponseCode.OK, "Historical returns reconciled",
                reconciliationService.reconcileHistoricalReturns());
    }

    /** GST operations that failed and are awaiting review. */
    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/exceptions")
    public ResponseEntity<Response> exceptions(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return data(ResponseCode.FETCHED, "GST exceptions fetched",
                exceptionRepo.findFiltered(status, org.springframework.data.domain.PageRequest.of(page, size)));
    }

    /** Immutable audit history. */
    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/audit")
    public ResponseEntity<Response> audit(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return data(ResponseCode.FETCHED, "GST audit log fetched",
                auditRepo.findFiltered(entityType, period,
                        org.springframework.data.domain.PageRequest.of(page, size)));
    }
}
