package com.app.master.service.service.admin;

import com.app.master.service.repository.admin.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks that the books agree with the records they were built from.
 *
 * Each check compares a figure derived from the general ledger against the same
 * figure derived from its source table. They should be identical — the ledger
 * was posted from those very records — so any difference means a transaction
 * was missed, double-posted, or posted to the wrong account.
 *
 * A difference is reported with its amount. A check that only said "FAIL" would
 * leave someone hunting for the size of the problem.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountingReconciliationService {

    public record Check(String name, String status, long differencePaise,
                        long ledgerPaise, long sourcePaise, String detail) {}

    public record Report(String period, List<Check> checks,
                         int passed, int warnings, int failed) {}

    private final GeneralLedgerService ledger;
    private final CustomerOrderRepository orderRepo;
    private final ExpenseRepository expenseRepo;
    private final SalaryPaymentRepository salaryRepo;
    private final GstMovementLedgerRepository movementRepo;
    private final GstPaymentRepository gstPaymentRepo;

    public Report reconcile(String period) {
        List<Check> checks = new ArrayList<>();
        YearMonth ym = YearMonth.parse(period);

        // 1. The ledger itself must balance before anything else is meaningful.
        var tb = ledger.trialBalance(period);
        checks.add(new Check("Trial balance: debits equal credits",
                tb.balanced() ? "PASS" : "FAIL", tb.differencePaise(),
                tb.totalDebitPaise(), tb.totalCreditPaise(),
                tb.balanced() ? "Every posted entry balances"
                        : "The ledger does not balance — nothing derived from it can be trusted"));

        // 2. The accounting equation.
        var bs = ledger.balanceSheet(period);
        checks.add(new Check("Assets = Liabilities + Equity",
                bs.balanced() ? "PASS" : "FAIL", bs.differencePaise(),
                bs.totalAssetsPaise(), bs.totalLiabilitiesPaise() + bs.totalEquityPaise(),
                bs.balanced() ? "The balance sheet closes"
                        : "Assets and the claims against them differ"));

        // 3. Revenue in the ledger against the orders it was posted from.
        long ledgerRevenue = ledger.profitAndLoss(period).revenuePaise();
        long sourceRevenue = orderRepo.findByArchiveFalseOrderByIdAsc().stream()
                .filter(o -> o.getOrderPlacedAt() != null)
                .filter(o -> YearMonth.from(java.time.LocalDate.ofInstant(
                        o.getOrderPlacedAt(), java.time.ZoneId.of("Asia/Kolkata"))).equals(ym))
                .mapToLong(o -> nz(o.getTaxableValue())).sum();
        checks.add(compare("Revenue: ledger vs orders", ledgerRevenue, sourceRevenue,
                "Sales posted to the ledger against the orders behind them"));

        // 4. Payroll cost.
        long ledgerPayroll = accountMovement(period, AccountingPostingService.PAYROLL);
        long sourcePayroll = salaryRepo.findAll().stream()
                .filter(s -> period.equals(s.getPaymentMonth()))
                .mapToLong(s -> nz(s.getTotalGrossPaise()) + nz(s.getTotalEmployerContributionPaise()))
                .sum();
        checks.add(compare("Payroll: ledger vs payroll runs", ledgerPayroll, sourcePayroll,
                "Gross pay plus employer contributions"));

        // 5. Expenses, excluding payroll which is checked on its own.
        long ledgerExpenses = ledger.profitAndLoss(period).operatingExpensePaise() - ledgerPayroll;
        long sourceExpenses = expenseRepo.findAll().stream()
                .filter(e -> e.getExpenseDate() != null && YearMonth.from(e.getExpenseDate()).equals(ym))
                .mapToLong(e -> {
                    boolean creditable = e.getSupplierGstin() != null && !e.getSupplierGstin().isBlank();
                    return creditable ? nz(e.getSubtotalPaise())
                                      : nz(e.getSubtotalPaise()) + nz(e.getTaxPaise());
                }).sum();
        checks.add(compare("Expenses: ledger vs vouchers", ledgerExpenses, sourceExpenses,
                "Net of creditable tax; non-creditable tax is charged to the expense"));

        // 6. Output GST held as a liability against the tax subledger.
        long ledgerOutput = accountMovement(period, AccountingPostingService.OUTPUT_CGST)
                + accountMovement(period, AccountingPostingService.OUTPUT_SGST)
                + accountMovement(period, AccountingPostingService.OUTPUT_IGST);
        long sourceOutput = movementRepo.findByTaxPeriodOrderByCreatedAtDesc(period).stream()
                .filter(m -> "POSTED".equals(m.getStatus()))
                .filter(m -> "OUT".equals(m.getDirection()) || "ADJUSTMENT".equals(m.getDirection()))
                .mapToLong(m -> nz(m.getTotalTaxPaise())).sum();
        long gstDiff = ledgerOutput - sourceOutput;
        checks.add(new Check("Output GST: books vs GST subledger",
                gstDiff == 0 ? "PASS" : "WARNING", Math.abs(gstDiff),
                ledgerOutput, sourceOutput,
                gstDiff == 0
                    ? "Books and the GST subledger agree"
                    : "The books post tax from each order; the GST subledger recomputes it at "
                    + "invoice level and carries returns as adjustments. A difference here is a "
                    + "reconciling item to explain, not necessarily an error."));

        // 7. GST paid.
        long ledgerPaid = accountMovement(period, AccountingPostingService.GST_PAYABLE);
        // Matched on payment date, not tax period: a payment made in September
        // for August's tax moves cash in September, and that is the period its
        // journal belongs to.
        long sourcePaid = gstPaymentRepo.findAll().stream()
                .filter(g -> g.getPaymentDate() != null
                        && YearMonth.from(g.getPaymentDate()).equals(ym))
                .mapToLong(g -> nz(g.getTotalPaise())).sum();
        checks.add(compare("GST payments: ledger vs payments made this period",
                ledgerPaid, sourcePaid,
                "Matched on the date cash moved, not the tax period being settled"));

        int passed = (int) checks.stream().filter(c -> "PASS".equals(c.status())).count();
        int warnings = (int) checks.stream().filter(c -> "WARNING".equals(c.status())).count();
        int failed = (int) checks.stream().filter(c -> "FAIL".equals(c.status())).count();

        return new Report(period, checks, passed, warnings, failed);
    }

    /**
     * A difference of zero passes. Anything else fails — these figures are
     * posted from the same records, so they are either equal or something is
     * wrong. There is no tolerance band that would be honest here.
     */
    private Check compare(String name, long ledgerValue, long sourceValue, String detail) {
        long diff = ledgerValue - sourceValue;
        String status = diff == 0 ? "PASS" : (sourceValue == 0 && ledgerValue == 0 ? "PASS" : "FAIL");
        return new Check(name, status, Math.abs(diff), ledgerValue, sourceValue, detail);
    }

    private long accountMovement(String period, String accountCode) {
        return ledger.ledger(period, accountCode, null).stream()
                .mapToLong(r -> r.debitPaise() - r.creditPaise())
                .sum() * (isCreditAccount(accountCode) ? -1 : 1);
    }

    private boolean isCreditAccount(String code) {
        return code.startsWith("2") || code.startsWith("3") || code.startsWith("4");
    }

    private static long nz(Long v) { return v == null ? 0L : v; }
}
