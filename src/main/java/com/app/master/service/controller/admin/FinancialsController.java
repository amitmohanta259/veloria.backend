package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.FinancialReportsService;
import com.app.master.service.service.admin.FinancialsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;

/**
 * The financial position for a period.
 *
 * Read-only: every figure is derived from documents recorded elsewhere, so
 * there is nothing here to write.
 */
@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/financials")
@RequiredArgsConstructor
public class FinancialsController extends AppController {

    private final FinancialsService financialsService;
    private final FinancialReportsService reportsService;

    /** Headline metrics, the period's P&L, and what it cannot account for. */
    @GetMapping("/summary")
    public ResponseEntity<Response> summary(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "Financial summary",
                financialsService.summary(period));
    }

    /** The month-by-month trend behind the chart. */
    @GetMapping("/trend")
    public ResponseEntity<Response> trend(@RequestParam(defaultValue = "6") int months) {
        return data(ResponseCode.FETCHED, "Financial trend", financialsService.trend(months));
    }

    /** Every real money movement, newest first. */
    @GetMapping("/ledger")
    public ResponseEntity<Response> ledger(@RequestParam(required = false) String period,
                                           @RequestParam(required = false) String category) {
        return data(ResponseCode.FETCHED, "Financial ledger",
                financialsService.ledger(period, category));
    }

    /** The full profit and loss for one period. */
    @GetMapping("/profit-loss")
    public ResponseEntity<Response> profitAndLoss(@RequestParam(required = false) String period) {
        YearMonth ym = period == null || period.isBlank()
                ? YearMonth.now(java.time.ZoneId.of("Asia/Kolkata"))
                : YearMonth.parse(period);
        return data(ResponseCode.FETCHED, "Profit and loss",
                financialsService.profitAndLoss(ym));
    }

    /** The profit and loss statement, broken into line items. */
    @GetMapping("/profit-loss/statement")
    public ResponseEntity<Response> statement(@RequestParam(required = false) String period) {
        YearMonth ym = period == null || period.isBlank()
                ? YearMonth.now(java.time.ZoneId.of("Asia/Kolkata"))
                : YearMonth.parse(period);
        return data(ResponseCode.FETCHED, "Profit and loss statement",
                financialsService.profitAndLossStatement(ym));
    }

    // ── Reporting hub statements ─────────────────────────────────────────────

    private YearMonth month(String period) {
        return period == null || period.isBlank()
                ? YearMonth.now(java.time.ZoneId.of("Asia/Kolkata"))
                : YearMonth.parse(period);
    }

    @GetMapping("/balance-sheet")
    public ResponseEntity<Response> balanceSheet(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "Balance sheet", reportsService.balanceSheet(month(period)));
    }

    @GetMapping("/account-ledger")
    public ResponseEntity<Response> accountLedger(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "Account ledger", reportsService.accountLedger(period));
    }

    @GetMapping("/liquidity")
    public ResponseEntity<Response> liquidity(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "Liquidity analysis", reportsService.liquidity(month(period)));
    }

    @GetMapping("/gst-report")
    public ResponseEntity<Response> gstReport(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "GST and tax report", reportsService.gstReport(month(period)));
    }

    @GetMapping("/opex-report")
    public ResponseEntity<Response> opexReport(@RequestParam(required = false) String period) {
        return data(ResponseCode.FETCHED, "Operating expenses", reportsService.opexReport(month(period)));
    }
}
