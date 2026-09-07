package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.AccountingPeriodRepository;
import com.app.master.service.repository.admin.ChartOfAccountRepository;
import com.app.master.service.service.admin.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;

/**
 * The accounting layer: chart of accounts, journals, and the statements derived
 * from them.
 *
 * Reports here read the general ledger, so they change together when a
 * transaction is posted.
 */
@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/accounting")
@RequiredArgsConstructor
public class AccountingController extends AppController {

    private final JournalService journalService;
    private final AccountingPostingService postingService;
    private final GeneralLedgerService ledgerService;
    private final AccountingReconciliationService reconciliationService;
    private final OpeningBalanceService openingBalanceService;
    private final ChartOfAccountRepository accountRepo;
    private final AccountingPeriodRepository periodRepo;

    private String month(String period) {
        return period == null || period.isBlank()
                ? YearMonth.now(java.time.ZoneId.of("Asia/Kolkata")).toString()
                : period;
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    @GetMapping("/accounts")
    public ResponseEntity<Response> accounts() {
        return data(ResponseCode.FETCHED, "Chart of accounts",
                accountRepo.findByActiveTrueOrderByCodeAsc());
    }

    /** Posts every business record that has no journal entry yet. Idempotent. */
    @PostMapping("/backfill")
    public ResponseEntity<Response> backfill() {
        return data(ResponseCode.CREATED, "Accounting backfill complete", postingService.backfill());
    }

    // ── Periods ──────────────────────────────────────────────────────────────

    @GetMapping("/periods")
    public ResponseEntity<Response> periods() {
        return data(ResponseCode.FETCHED, "Accounting periods", ledgerService.periods());
    }

    /** The one period every report reads, with its exact start and end. */
    @GetMapping("/period")
    public ResponseEntity<Response> period(@RequestParam(required = false) String period) {
        String p = month(period);
        YearMonth ym = YearMonth.parse(p);
        return data(ResponseCode.FETCHED, "Reporting period", Map.of(
                "period", p,
                "periodStart", ym.atDay(1).toString(),
                "periodEnd", ym.atEndOfMonth().toString(),
                "financialYear", JournalService.financialYear(ym.atDay(1)),
                "selectedMonth", p,
                "status", ledgerService.period(p)
                        .map(a -> a.getStatus()).orElse("OPEN")));
    }

    @PostMapping("/periods/{period}/close")
    public ResponseEntity<Response> close(@PathVariable String period,
                                          @RequestBody(required = false) Map<String, String> body)
            throws VeloriaException {
        var p = periodRepo.findByPeriod(period)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Accounting period " + period + " was not found"));
        if (!"OPEN".equals(p.getStatus())) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "Period " + period + " is already " + p.getStatus());
        }
        p.setStatus("CLOSED");
        p.setClosedAt(Instant.now());
        p.setClosedBy(body == null ? null : body.get("actor"));
        return data(ResponseCode.UPDATED, "Period closed", periodRepo.save(p));
    }

    @PostMapping("/periods/{period}/reopen")
    public ResponseEntity<Response> reopen(@PathVariable String period,
                                           @RequestBody Map<String, String> body)
            throws VeloriaException {
        String reason = body == null ? null : body.get("reason");
        if (reason == null || reason.isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Reopening a closed period needs a reason — it is audited");
        }
        var p = periodRepo.findByPeriod(period)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Accounting period " + period + " was not found"));
        p.setStatus("OPEN");
        return data(ResponseCode.UPDATED, "Period reopened", periodRepo.save(p));
    }

    // ── Statements ───────────────────────────────────────────────────────────

    @GetMapping("/trial-balance")
    public ResponseEntity<Response> trialBalance(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "Trial balance",
                ledgerService.trialBalance(month(period)));
    }

    @GetMapping("/profit-loss")
    public ResponseEntity<Response> profitAndLoss(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "Profit and loss",
                ledgerService.profitAndLoss(month(period)));
    }

    @GetMapping("/balance-sheet")
    public ResponseEntity<Response> balanceSheet(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "Balance sheet",
                ledgerService.balanceSheet(month(period)));
    }

    /** The general ledger, filterable by account and by what produced the entry. */
    @GetMapping("/ledger")
    public ResponseEntity<Response> ledger(@RequestParam(required = false) String period,
                                           @RequestParam(required = false) String accountCode,
                                           @RequestParam(required = false) String sourceType) {
        return data(ResponseCode.FETCHED, "General ledger",
                ledgerService.ledger(month(period), accountCode, sourceType));
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<Response> reconciliation(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "Financial reconciliation",
                reconciliationService.reconcile(month(period)));
    }

    // ── Journals ─────────────────────────────────────────────────────────────

    @GetMapping("/journals")
    public ResponseEntity<Response> journals(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "Journal entries", journalService.forPeriod(month(period)));
    }

    @GetMapping("/journals/{id}/lines")
    public ResponseEntity<Response> journalLines(@PathVariable Long id) {
        return data(ResponseCode.FETCHED, "Journal lines", journalService.linesOf(id));
    }

    /** Corrects a posted entry by its mirror image. The original is retained. */
    @PostMapping("/journals/{id}/reverse")
    public ResponseEntity<Response> reverse(@PathVariable Long id,
                                            @RequestBody Map<String, String> body)
            throws VeloriaException {
        return data(ResponseCode.UPDATED, "Journal reversed",
                journalService.reverse(id, body == null ? null : body.get("reason")));
    }

    // ── Opening balances ─────────────────────────────────────────────────────

    /** What has been provided and what is still outstanding. */
    @GetMapping("/opening-balances")
    public ResponseEntity<Response> openingBalances() {
        return data(ResponseCode.FETCHED, "Opening balance readiness",
                openingBalanceService.readiness());
    }

    /**
     * Posts opening balances as one journal.
     *
     * Refused unless debits equal credits — no balancing figure is invented, and
     * an account left out of the request is left out of the books rather than
     * being treated as zero.
     */
    @PostMapping("/opening-balances")
    public ResponseEntity<Response> postOpeningBalances(@RequestBody Map<String, Object> body)
            throws VeloriaException {
        Object rawDate = body.get("asOfDate");
        if (rawDate == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "asOfDate is required — it fixes the point the books start from");
        }
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> rawLines =
                (java.util.List<Map<String, Object>>) body.get("lines");
        if (rawLines == null || rawLines.isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "lines are required");
        }

        java.util.List<OpeningBalanceService.OpeningLine> lines = rawLines.stream()
                .map(l -> new OpeningBalanceService.OpeningLine(
                        String.valueOf(l.get("accountCode")),
                        ((Number) l.get("amountPaise")).longValue()))
                .toList();

        return data(ResponseCode.CREATED, "Opening balances posted",
                openingBalanceService.post(new OpeningBalanceService.Submission(
                        java.time.LocalDate.parse(String.valueOf(rawDate)),
                        lines, (String) body.get("note"))));
    }
}
