package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.entity.GstPaymentEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.GstItcTransactionRepository;
import com.app.master.service.repository.admin.GstPaymentRepository;
import com.app.master.service.service.admin.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ITC, GSTR-2B, tax periods, returns, debit notes and payments.
 *
 * Every endpoint is authenticated and carries the permission it requires; no
 * financial value is taken from the request body without recalculation.
 */
@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/gst/compliance")
@RequiredArgsConstructor
public class GstComplianceController extends AppController {

    private final GstItcService itcService;
    private final Gstr2bService gstr2bService;
    private final GstTaxPeriodService periodService;
    private final GstReturnPreparationService returnService;
    private final GstDebitNoteService debitNoteService;
    private final GstItcTransactionRepository itcTxnRepo;
    private final GstPaymentRepository paymentRepo;
    private final GstHistoricalExceptionService exceptionService;
    private final GstInterestLateFeeService interestLateFeeService;
    private final GstComplianceDocumentService complianceDocumentService;
    private final GstIdentityService identityService;
    private final GstAuditService auditService;
    private final com.app.master.service.core.security.GstSecurityContext securityContext;

    // ── ITC ──────────────────────────────────────────────────────────────────

    @PreAuthorize("hasAuthority('VIEW_ITC')")
    @GetMapping("/itc/reason-codes")
    public ResponseEntity<Response> reasonCodes() {
        return data(ResponseCode.FETCHED, "ITC reason codes", Map.of(
                "eligibilityStates", GstItcService.ELIGIBILITY_STATES,
                "reasonCodes", GstItcService.REASON_CODES));
    }

    @PreAuthorize("hasAuthority('VIEW_ITC')")
    @GetMapping("/itc/{inputTaxId}/transactions")
    public ResponseEntity<Response> itcTransactions(@PathVariable Long inputTaxId) {
        return data(ResponseCode.FETCHED, "ITC transactions", itcService.transactionsFor(inputTaxId));
    }

    @PreAuthorize("hasAuthority('VIEW_ITC')")
    @GetMapping("/itc/transactions")
    public ResponseEntity<Response> itcLedger(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) throws VeloriaException {
        return data(ResponseCode.FETCHED, "ITC ledger",
                itcTxnRepo.findFiltered(securityContext.organizationId(), period, type,
                        PageRequest.of(page, size)));
    }

    @PreAuthorize("hasAuthority('APPROVE_ITC')")
    @PostMapping("/itc/{inputTaxId}/eligibility")
    public ResponseEntity<Response> setEligibility(
            @PathVariable Long inputTaxId,
            @RequestBody EligibilityRequest body) throws VeloriaException {
        GstItcService.TaxHeads split = body.getEligibleCgstPaise() == null ? null
                : new GstItcService.TaxHeads(
                        nz(body.getEligibleCgstPaise()), nz(body.getEligibleSgstPaise()),
                        nz(body.getEligibleIgstPaise()));
        return data(ResponseCode.UPDATED, "ITC eligibility recorded",
                itcService.setEligibility(inputTaxId, body.getEligibility(),
                        body.getReasonCode(), body.getNotes(), split));
    }

    @PreAuthorize("hasAuthority('APPROVE_ITC')")
    @PostMapping("/itc/{inputTaxId}/claim")
    public ResponseEntity<Response> claimItc(
            @PathVariable Long inputTaxId,
            @RequestBody(required = false) Map<String, String> body) throws VeloriaException {
        return data(ResponseCode.OK, "ITC claimed",
                itcService.claim(inputTaxId, body != null ? body.get("notes") : null));
    }

    @PreAuthorize("hasAuthority('REVERSE_ITC')")
    @PostMapping("/itc/{inputTaxId}/reverse")
    public ResponseEntity<Response> reverseItc(
            @PathVariable Long inputTaxId,
            @RequestBody ItcAdjustmentRequest body) throws VeloriaException {
        return data(ResponseCode.OK, "ITC reversed",
                itcService.reverse(inputTaxId, headsOf(body), body.getReasonCode(), body.getNotes()));
    }

    @PreAuthorize("hasAuthority('RECLAIM_ITC')")
    @PostMapping("/itc/{inputTaxId}/reclaim")
    public ResponseEntity<Response> reclaimItc(
            @PathVariable Long inputTaxId,
            @RequestBody ItcAdjustmentRequest body) throws VeloriaException {
        return data(ResponseCode.OK, "ITC reclaimed",
                itcService.reclaim(inputTaxId, headsOf(body), body.getReasonCode(), body.getNotes()));
    }

    // ── GSTR-2B ──────────────────────────────────────────────────────────────

    @PreAuthorize("hasAuthority('RECONCILE_GSTR2B')")
    @PostMapping("/gstr2b/import")
    public ResponseEntity<Response> importGstr2b(
            @RequestParam String period,
            @RequestParam(defaultValue = "CSV") String format,
            @RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "The GSTR-2B file is empty");
        }
        return data(ResponseCode.IMPORTED, "GSTR-2B imported",
                gstr2bService.importFile(period, file.getOriginalFilename(), format, file.getBytes()));
    }

    @PreAuthorize("hasAuthority('RECONCILE_GSTR2B')")
    @PostMapping("/gstr2b/{period}/reconcile")
    public ResponseEntity<Response> reconcile(@PathVariable String period) {
        return data(ResponseCode.OK, "GSTR-2B reconciled", gstr2bService.reconcile(period));
    }

    @PreAuthorize("hasAuthority('VIEW_ITC')")
    @GetMapping("/gstr2b/{period}/records")
    public ResponseEntity<Response> gstr2bRecords(@PathVariable String period) {
        return data(ResponseCode.FETCHED, "GSTR-2B records", gstr2bService.recordsFor(period));
    }

    @PreAuthorize("hasAuthority('VIEW_ITC')")
    @GetMapping("/gstr2b/{period}/reconciliation")
    public ResponseEntity<Response> reconciliationRows(@PathVariable String period) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusCounts", gstr2bService.statusCounts(period));
        out.put("rows", gstr2bService.reconciliationFor(period));
        return data(ResponseCode.FETCHED, "Reconciliation", out);
    }

    // ── Tax periods ──────────────────────────────────────────────────────────

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/periods")
    public ResponseEntity<Response> periods() {
        return data(ResponseCode.FETCHED, "Tax periods", periodService.list());
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/periods/{period}")
    public ResponseEntity<Response> period(@PathVariable String period) {
        return data(ResponseCode.FETCHED, "Tax period", periodService.get(period));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @PostMapping("/periods/{period}/recompute")
    public ResponseEntity<Response> recompute(@PathVariable String period) {
        return data(ResponseCode.UPDATED, "Tax period recomputed", periodService.recompute(period));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/periods/{period}/validate")
    public ResponseEntity<Response> validate(@PathVariable String period) {
        return data(ResponseCode.FETCHED, "Validation report", periodService.validationReport(period));
    }

    @PreAuthorize("hasAuthority('LOCK_PERIOD')")
    @PostMapping("/periods/{period}/ready-for-filing")
    public ResponseEntity<Response> readyForFiling(@PathVariable String period) throws VeloriaException {
        return data(ResponseCode.UPDATED, "Period marked ready for filing",
                periodService.markReadyForFiling(period));
    }

    @PreAuthorize("hasAuthority('LOCK_PERIOD')")
    @PostMapping("/periods/{period}/lock")
    public ResponseEntity<Response> lock(@PathVariable String period) throws VeloriaException {
        return data(ResponseCode.UPDATED, "Period locked", periodService.lock(period));
    }

    @PreAuthorize("hasAuthority('UNLOCK_PERIOD')")
    @PostMapping("/periods/{period}/unlock")
    public ResponseEntity<Response> unlock(@PathVariable String period,
                                            @RequestBody Map<String, String> body) throws VeloriaException {
        return data(ResponseCode.UPDATED, "Period unlocked",
                periodService.unlock(period, body != null ? body.get("reason") : null));
    }

    // ── Returns ──────────────────────────────────────────────────────────────

    @PreAuthorize("hasAuthority('PREPARE_GSTR1')")
    @PostMapping("/returns/gstr1/{period}/prepare")
    public ResponseEntity<Response> prepareGstr1(@PathVariable String period) throws VeloriaException {
        return data(ResponseCode.OK, "GSTR-1 prepared (not filed)", returnService.prepareGstr1(period));
    }

    @PreAuthorize("hasAuthority('PREPARE_GSTR3B')")
    @PostMapping("/returns/gstr3b/{period}/prepare")
    public ResponseEntity<Response> prepareGstr3b(@PathVariable String period) throws VeloriaException {
        return data(ResponseCode.OK, "GSTR-3B prepared (not filed)", returnService.prepareGstr3b(period));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/returns")
    public ResponseEntity<Response> returns() {
        return data(ResponseCode.FETCHED, "Return snapshots", returnService.snapshots());
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/returns/{id}/regeneration-required")
    public ResponseEntity<Response> regenerationRequired(@PathVariable Long id) throws VeloriaException {
        return data(ResponseCode.FETCHED, "Regeneration check",
                Map.of("requiresRegeneration", returnService.checkRequiresRegeneration(id)));
    }

    @PreAuthorize("hasAuthority('VIEW_GST_LEDGER')")
    @GetMapping("/returns/{period}/trace")
    public ResponseEntity<Response> trace(@PathVariable String period,
                                           @RequestParam(required = false) String movementType) {
        return data(ResponseCode.FETCHED, "Source movements", returnService.trace(period, movementType));
    }

    @PreAuthorize("hasAuthority('PREPARE_GSTR1')")
    @PostMapping("/returns/{id}/status")
    public ResponseEntity<Response> setReturnStatus(@PathVariable Long id,
                                                     @RequestBody Map<String, String> body)
            throws VeloriaException {
        return data(ResponseCode.UPDATED, "Return status updated",
                returnService.transition(id, body.get("status")));
    }

    /** Records a real filing. Refuses without an acknowledgement number. */
    @PreAuthorize("hasAuthority('FILE_RETURN')")
    @PostMapping("/returns/{id}/record-filing")
    public ResponseEntity<Response> recordFiling(@PathVariable Long id,
                                                  @RequestBody Map<String, String> body)
            throws VeloriaException {
        return data(ResponseCode.UPDATED, "Filing recorded",
                returnService.recordFiling(id,
                        body.get("acknowledgementNumber"), body.get("filingReference")));
    }

    // ── Debit notes ──────────────────────────────────────────────────────────

    @PreAuthorize("hasAuthority('CREATE_DEBIT_NOTE')")
    @PostMapping("/debit-notes/order/{orderCode}")
    public ResponseEntity<Response> createDebitNote(@PathVariable String orderCode,
                                                     @RequestBody DebitNoteRequest body)
            throws VeloriaException {
        List<GstDebitNoteService.DebitLine> lines = body.getItems() == null ? List.of()
                : body.getItems().stream()
                    .map(i -> new GstDebitNoteService.DebitLine(
                            i.getOrderItemId(), i.getQuantity(), i.getAdditionalTaxablePaise()))
                    .toList();
        return data(ResponseCode.CREATED, "Debit note created",
                debitNoteService.create(orderCode, lines, body.getReasonCode(), body.getNotes()));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/debit-notes/{id}/items")
    public ResponseEntity<Response> debitNoteItems(@PathVariable Long id) {
        return data(ResponseCode.FETCHED, "Debit note items", debitNoteService.itemsOf(id));
    }

    @PreAuthorize("hasAuthority('APPROVE_ITC')")
    @PostMapping("/debit-notes/{id}/approve")
    public ResponseEntity<Response> approveDebitNote(@PathVariable Long id) throws VeloriaException {
        return data(ResponseCode.UPDATED, "Debit note approved",
                debitNoteService.approve(id, securityContext.actor()));
    }

    @PreAuthorize("hasAuthority('CREATE_DEBIT_NOTE')")
    @PostMapping("/debit-notes/{id}/issue")
    public ResponseEntity<Response> issueDebitNote(@PathVariable Long id) throws VeloriaException {
        return data(ResponseCode.UPDATED, "Debit note issued",
                debitNoteService.issue(id, securityContext.actor()));
    }

    // ── Payments ─────────────────────────────────────────────────────────────

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/payments")
    public ResponseEntity<Response> payments(@RequestParam(required = false) String period)
            throws VeloriaException {
        Long org = securityContext.organizationId();
        return data(ResponseCode.FETCHED, "GST payments",
                period != null
                        ? paymentRepo.findByOrganizationIdAndTaxPeriodOrderByIdAsc(org, period)
                        : paymentRepo.findByOrganizationIdOrderByIdDesc(org));
    }

    @PreAuthorize("hasAuthority('ADMIN_GST')")
    @PostMapping("/payments")
    public ResponseEntity<Response> recordPayment(@RequestBody PaymentRequest body)
            throws VeloriaException {
        if (body.getPaymentReference() == null || body.getPaymentReference().isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "A payment reference is required");
        }
        if (paymentRepo.existsByPaymentReference(body.getPaymentReference())) {
            throw new VeloriaException(ResponseCode.ALREADY_EXIST,
                    "A payment with reference " + body.getPaymentReference() + " already exists");
        }
        periodService.assertOpen(body.getTaxPeriod());

        long total = nz(body.getCgstPaise()) + nz(body.getSgstPaise()) + nz(body.getIgstPaise())
                + nz(body.getCessPaise()) + nz(body.getInterestPaise()) + nz(body.getLateFeePaise());

        GstPaymentEntity p = paymentRepo.save(GstPaymentEntity.builder()
                .paymentReference(body.getPaymentReference())
                .paymentDate(body.getPaymentDate() != null
                        ? LocalDate.parse(body.getPaymentDate()) : LocalDate.now())
                .taxPeriod(body.getTaxPeriod())
                .financialYear(YearMonth.parse(body.getTaxPeriod()).getMonthValue() >= 4
                        ? YearMonth.parse(body.getTaxPeriod()).getYear() + "-"
                            + String.format("%02d", (YearMonth.parse(body.getTaxPeriod()).getYear() + 1) % 100)
                        : (YearMonth.parse(body.getTaxPeriod()).getYear() - 1) + "-"
                            + String.format("%02d", YearMonth.parse(body.getTaxPeriod()).getYear() % 100))
                .cgstPaise(nz(body.getCgstPaise()))
                .sgstPaise(nz(body.getSgstPaise()))
                .igstPaise(nz(body.getIgstPaise()))
                .cessPaise(nz(body.getCessPaise()))
                .interestPaise(nz(body.getInterestPaise()))
                .lateFeePaise(nz(body.getLateFeePaise()))
                .totalPaise(total)
                .paymentMode(body.getPaymentMode())
                .challanNumber(body.getChallanNumber())
                .notes(body.getNotes())
                .organizationId(securityContext.organizationId())
                .gstRegistrationId(securityContext.gstRegistrationId())
                .createdBy(securityContext.actor())
                .build());

        auditService.log("GST_PAYMENT", p.getId(), p.getPaymentReference(), "TAX_PAYMENT_RECORDED",
                p.getTaxPeriod(), securityContext.actor());
        return data(ResponseCode.CREATED, "GST payment recorded", p);
    }

    // ── Request bodies ───────────────────────────────────────────────────────

    private static GstItcService.TaxHeads headsOf(ItcAdjustmentRequest b) {
        if (b.getCgstPaise() == null && b.getSgstPaise() == null && b.getIgstPaise() == null) return null;
        return new GstItcService.TaxHeads(nz(b.getCgstPaise()), nz(b.getSgstPaise()), nz(b.getIgstPaise()));
    }

    private static long nz(Long v) { return v != null ? v : 0L; }

    @lombok.Data
    public static class EligibilityRequest {
        private String eligibility;
        private String reasonCode;
        private String notes;
        private Long eligibleCgstPaise;
        private Long eligibleSgstPaise;
        private Long eligibleIgstPaise;
    }

    @lombok.Data
    public static class ItcAdjustmentRequest {
        private Long cgstPaise;
        private Long sgstPaise;
        private Long igstPaise;
        private String reasonCode;
        private String notes;
    }

    @lombok.Data
    public static class DebitNoteRequest {
        private List<DebitNoteItem> items;
        private String reasonCode;
        private String notes;
    }

    @lombok.Data
    public static class DebitNoteItem {
        private Long orderItemId;
        private Integer quantity;
        /** Additional taxable value only — tax is computed on the backend. */
        private Long additionalTaxablePaise;
    }

    @lombok.Data
    public static class PaymentRequest {
        private String paymentReference;
        private String paymentDate;
        private String taxPeriod;
        private Long cgstPaise;
        private Long sgstPaise;
        private Long igstPaise;
        private Long cessPaise;
        private Long interestPaise;
        private Long lateFeePaise;
        private String paymentMode;
        private String challanNumber;
        private String notes;
    }

    // ── Historical GST exceptions (phases 4 and 5) ───────────────────────────

    /**
     * Re-runs historical detection. Read-heavy and idempotent: a finding already
     * recorded updates in place, and one already reviewed is left alone.
     */
    @PreAuthorize("hasAuthority('ADMIN_GST')")
    @PostMapping("/exceptions/scan")
    public ResponseEntity<Response> scanExceptions() throws VeloriaException {
        return data(ResponseCode.FETCHED, "Historical GST scan complete", exceptionService.scan());
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/exceptions/dashboard")
    public ResponseEntity<Response> exceptionDashboard(
            @RequestParam(required = false) String taxPeriod) {
        return data(ResponseCode.FETCHED, "GST exception dashboard",
                exceptionService.dashboard(taxPeriod));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/exceptions/queue")
    public ResponseEntity<Response> exceptionQueue(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String taxPeriod) {
        return data(ResponseCode.FETCHED, "GST exceptions",
                exceptionService.queue(status, taxPeriod));
    }

    /** Moves an exception through review. The status is the only thing accepted. */
    @PreAuthorize("hasAuthority('APPROVE_ITC')")
    @PatchMapping("/exceptions/{id}/review")
    public ResponseEntity<Response> reviewException(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) throws VeloriaException {
        return data(ResponseCode.UPDATED, "GST exception reviewed",
                exceptionService.review(id, body.get("status"), body.get("comments")));
    }

    // ── Interest and late fee (phase 11) ─────────────────────────────────────

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/interest-late-fee/configuration")
    public ResponseEntity<Response> interestConfiguration() {
        return data(ResponseCode.FETCHED, "Interest and late-fee configuration",
                interestLateFeeService.configuration());
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/interest-late-fee/interest")
    public ResponseEntity<Response> computeInterest(
            @RequestParam String ruleType,
            @RequestParam long outstandingPaise,
            @RequestParam int days,
            @RequestParam(required = false) String onDate) {
        return data(ResponseCode.FETCHED, "Interest computation",
                interestLateFeeService.interest(ruleType, outstandingPaise, days,
                        onDate == null ? null : java.time.LocalDate.parse(onDate)));
    }

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/interest-late-fee/late-fee")
    public ResponseEntity<Response> computeLateFee(
            @RequestParam String returnType,
            @RequestParam int daysLate,
            @RequestParam(required = false) String onDate) {
        return data(ResponseCode.FETCHED, "Late-fee computation",
                interestLateFeeService.lateFee(returnType, daysLate,
                        onDate == null ? null : java.time.LocalDate.parse(onDate)));
    }

    @PreAuthorize("hasAuthority('ADMIN_GST')")
    @PostMapping("/interest-late-fee/interest-rules")
    public ResponseEntity<Response> addInterestRule(@RequestBody Map<String, Object> body)
            throws VeloriaException {
        return data(ResponseCode.CREATED, "Interest rule added",
                interestLateFeeService.addInterestRule(
                        (String) body.get("ruleType"),
                        ((Number) body.get("rateBp")).intValue(),
                        java.time.LocalDate.parse((String) body.get("effectiveFrom")),
                        body.get("effectiveTo") == null ? null
                                : java.time.LocalDate.parse((String) body.get("effectiveTo")),
                        (String) body.get("sourceReference"),
                        (String) body.get("description")));
    }

    @PreAuthorize("hasAuthority('ADMIN_GST')")
    @PostMapping("/interest-late-fee/late-fee-rules")
    public ResponseEntity<Response> addLateFeeRule(@RequestBody Map<String, Object> body)
            throws VeloriaException {
        return data(ResponseCode.CREATED, "Late-fee rule added",
                interestLateFeeService.addLateFeeRule(
                        (String) body.get("returnType"),
                        ((Number) body.get("perDayPaise")).longValue(),
                        body.get("maxPaise") == null ? null : ((Number) body.get("maxPaise")).longValue(),
                        java.time.LocalDate.parse((String) body.get("effectiveFrom")),
                        body.get("effectiveTo") == null ? null
                                : java.time.LocalDate.parse((String) body.get("effectiveTo")),
                        (String) body.get("sourceReference"),
                        (String) body.get("description")));
    }

    // ── Government integration status (phases 7, 8) ──────────────────────────

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/documents/provider-status")
    public ResponseEntity<Response> providerStatus() {
        return data(ResponseCode.FETCHED, "Government integration status",
                complianceDocumentService.providerStatus());
    }

    @PreAuthorize("hasAuthority('CREATE_INVOICE')")
    @PostMapping("/documents/einvoice/{documentId}/submit")
    public ResponseEntity<Response> submitEInvoice(@PathVariable Long documentId,
                                                   @RequestBody(required = false) Map<String, String> body)
            throws VeloriaException {
        return data(ResponseCode.UPDATED, "e-Invoice submitted",
                complianceDocumentService.submitEInvoice(documentId,
                        body == null ? null : body.get("payload")));
    }

    @PreAuthorize("hasAuthority('CREATE_INVOICE')")
    @PostMapping("/documents/ewaybill/{documentId}/submit")
    public ResponseEntity<Response> submitEWayBill(@PathVariable Long documentId,
                                                   @RequestBody(required = false) Map<String, String> body)
            throws VeloriaException {
        return data(ResponseCode.UPDATED, "e-Way bill submitted",
                complianceDocumentService.submitEWayBill(documentId,
                        body == null ? null : body.get("payload")));
    }
}
