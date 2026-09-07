package com.app.master.service.service.admin;

import com.app.master.service.core.entity.*;
import com.app.master.service.repository.admin.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * The financial statements, derived from the general ledger and nothing else.
 *
 * Every figure here is a sum over journal entry lines. That is the whole point:
 * the trial balance, the profit and loss and the balance sheet are three views
 * of one set of rows, so they cannot disagree. When a transaction is posted,
 * all three change together because none of them holds its own copy of the
 * arithmetic.
 *
 * Balances follow the account's normal side. An asset or expense increases on
 * the debit side, a liability, equity or revenue account on the credit side, so
 * a positive balance always means "more of what this account is for".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeneralLedgerService {

    private final JournalEntryRepository journalRepo;
    private final JournalEntryLineRepository lineRepo;
    private final ChartOfAccountRepository accountRepo;
    private final AccountingPeriodRepository periodRepo;

    // ── Shapes ───────────────────────────────────────────────────────────────

    public record TrialBalanceRow(String accountCode, String accountName, String accountType,
                                  long debitPaise, long creditPaise, long balancePaise) {}

    public record TrialBalance(String period, List<TrialBalanceRow> rows,
                               long totalDebitPaise, long totalCreditPaise,
                               boolean balanced, long differencePaise) {}

    public record StatementLine(String accountCode, String label, long amountPaise,
                                Double percentOfRevenue) {}

    public record StatementGroup(String title, List<StatementLine> lines, long subtotalPaise) {}

    public record ProfitAndLoss(String period,
                                List<StatementGroup> groups,
                                long revenuePaise, long cogsPaise, long grossProfitPaise,
                                long operatingExpensePaise, long netProfitPaise,
                                Double grossMarginPercent, Double netMarginPercent) {}

    public record BalanceSheet(String period, String asOf,
                               List<StatementGroup> assets,
                               List<StatementGroup> liabilities,
                               List<StatementGroup> equity,
                               long totalAssetsPaise, long totalLiabilitiesPaise,
                               long totalEquityPaise,
                               long retainedEarningsPaise, long currentPeriodProfitPaise,
                               boolean balanced, long differencePaise) {}

    public record LedgerRow(String date, String journalNumber, String accountCode,
                            String accountName, String reference, String sourceType,
                            String description, long debitPaise, long creditPaise,
                            long runningBalancePaise) {}

    // ── Trial balance ────────────────────────────────────────────────────────

    /**
     * Every account's debit and credit totals for the period.
     *
     * If this does not balance, nothing downstream can be trusted — which is
     * why it is reported with an explicit difference rather than a boolean
     * alone.
     */
    public TrialBalance trialBalance(String period) {
        Map<String, long[]> totals = new TreeMap<>();   // code -> {debit, credit}

        for (JournalEntryEntity e : entriesUpTo(period, false)) {
            for (JournalEntryLineEntity l : lineRepo.findByJournalEntryIdOrderByLineNumberAsc(e.getId())) {
                long[] agg = totals.computeIfAbsent(l.getAccountCode(), k -> new long[2]);
                agg[0] += nz(l.getDebitPaise());
                agg[1] += nz(l.getCreditPaise());
            }
        }

        List<TrialBalanceRow> rows = new ArrayList<>();
        long totalDebit = 0, totalCredit = 0;
        for (var entry : totals.entrySet()) {
            ChartOfAccountEntity a = account(entry.getKey());
            long dr = entry.getValue()[0], cr = entry.getValue()[1];
            totalDebit += dr;
            totalCredit += cr;
            rows.add(new TrialBalanceRow(entry.getKey(), name(a), type(a), dr, cr,
                    balanceOf(a, dr, cr)));
        }

        long diff = totalDebit - totalCredit;
        return new TrialBalance(period, rows, totalDebit, totalCredit, diff == 0, Math.abs(diff));
    }

    // ── Profit and loss ──────────────────────────────────────────────────────

    /** Revenue and expenses for one period, from the ledger. */
    public ProfitAndLoss profitAndLoss(String period) {
        Map<String, Long> byAccount = periodMovements(period);

        List<StatementLine> revenueLines = new ArrayList<>();
        List<StatementLine> cogsLines = new ArrayList<>();
        List<StatementLine> opexLines = new ArrayList<>();

        long revenue = 0, cogs = 0, opex = 0;
        for (var e : byAccount.entrySet()) {
            ChartOfAccountEntity a = account(e.getKey());
            if (a == null) continue;
            long amount = e.getValue();
            if (amount == 0) continue;

            switch (a.getAccountType()) {
                case "REVENUE" -> { revenue += amount; revenueLines.add(line(a, amount)); }
                case "EXPENSE" -> {
                    if ("Cost of sales".equals(a.getSubgroup())) {
                        cogs += amount; cogsLines.add(line(a, amount));
                    } else {
                        opex += amount; opexLines.add(line(a, amount));
                    }
                }
                default -> { /* balance sheet account, not a P&L line */ }
            }
        }

        long grossProfit = revenue - cogs;
        long netProfit = grossProfit - opex;

        List<StatementGroup> groups = new ArrayList<>();
        groups.add(new StatementGroup("Revenue", withPercent(revenueLines, revenue), revenue));
        if (!cogsLines.isEmpty()) {
            groups.add(new StatementGroup("Cost of goods sold", withPercent(cogsLines, revenue), cogs));
        }
        groups.add(new StatementGroup("Operating expenses", withPercent(opexLines, revenue), opex));

        return new ProfitAndLoss(period, groups, revenue, cogs, grossProfit, opex, netProfit,
                margin(grossProfit, revenue), margin(netProfit, revenue));
    }

    // ── Balance sheet ────────────────────────────────────────────────────────

    /**
     * Position as at the end of the period, cumulative from the first entry.
     *
     * Equity is real here, not a subtraction: retained earnings are the profit
     * of every period before this one, and the current period's profit is
     * carried separately, exactly as they would be in a closing entry. The
     * accounting equation is then checked rather than assumed.
     */
    public BalanceSheet balanceSheet(String period) {
        Map<String, Long> cumulative = cumulativeBalances(period);

        Map<String, List<StatementLine>> assetGroups = new LinkedHashMap<>();
        Map<String, List<StatementLine>> liabilityGroups = new LinkedHashMap<>();
        List<StatementLine> equityLines = new ArrayList<>();

        long totalAssets = 0, totalLiabilities = 0, postedEquity = 0;

        for (var e : cumulative.entrySet()) {
            ChartOfAccountEntity a = account(e.getKey());
            if (a == null) continue;
            long amount = e.getValue();
            if (amount == 0) continue;

            switch (a.getAccountType()) {
                case "ASSET" -> {
                    totalAssets += amount;
                    assetGroups.computeIfAbsent(group(a), k -> new ArrayList<>()).add(line(a, amount));
                }
                case "LIABILITY" -> {
                    totalLiabilities += amount;
                    liabilityGroups.computeIfAbsent(group(a), k -> new ArrayList<>()).add(line(a, amount));
                }
                case "EQUITY" -> { postedEquity += amount; equityLines.add(line(a, amount)); }
                default -> { /* revenue and expense close into equity below */ }
            }
        }

        // Profit before this period is retained earnings; this period's profit is
        // shown separately, the way a closing entry would carry it.
        long retained = profitBefore(period);
        long currentProfit = profitAndLoss(period).netProfitPaise();

        equityLines.add(new StatementLine("3020", "Retained earnings", retained, null));
        equityLines.add(new StatementLine("3030", "Current period profit", currentProfit, null));
        long totalEquity = postedEquity + retained + currentProfit;

        long difference = totalAssets - (totalLiabilities + totalEquity);

        return new BalanceSheet(period, YearMonth.parse(period).atEndOfMonth().toString(),
                toGroups(assetGroups), toGroups(liabilityGroups),
                List.of(new StatementGroup("Equity", equityLines, totalEquity)),
                totalAssets, totalLiabilities, totalEquity,
                retained, currentProfit,
                difference == 0, Math.abs(difference));
    }

    // ── Account ledger ───────────────────────────────────────────────────────

    /** Every posted line for a period, optionally one account, with a running balance. */
    public List<LedgerRow> ledger(String period, String accountCode, String sourceType) {
        List<LedgerRow> rows = new ArrayList<>();
        long running = 0;

        for (JournalEntryEntity e : journalRepo
                .findByPeriodAndStatusOrderByJournalDateAscIdAsc(period, JournalService.POSTED)) {
            if (sourceType != null && !sourceType.isBlank()
                    && !sourceType.equalsIgnoreCase(e.getSourceType())) continue;

            for (JournalEntryLineEntity l : lineRepo.findByJournalEntryIdOrderByLineNumberAsc(e.getId())) {
                if (accountCode != null && !accountCode.isBlank()
                        && !accountCode.equals(l.getAccountCode())) continue;

                ChartOfAccountEntity a = account(l.getAccountCode());
                running += nz(l.getDebitPaise()) - nz(l.getCreditPaise());
                rows.add(new LedgerRow(e.getJournalDate().toString(), e.getJournalNumber(),
                        l.getAccountCode(), name(a), nvl(e.getReference()), e.getSourceType(),
                        nvl(l.getDescription()).isBlank() ? nvl(e.getDescription()) : l.getDescription(),
                        nz(l.getDebitPaise()), nz(l.getCreditPaise()), running));
            }
        }
        return rows;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** Signed movement per account for one period, on the account's normal side. */
    private Map<String, Long> periodMovements(String period) {
        Map<String, Long> out = new TreeMap<>();
        for (JournalEntryEntity e : journalRepo
                .findByPeriodAndStatusOrderByJournalDateAscIdAsc(period, JournalService.POSTED)) {
            accumulate(out, e);
        }
        return out;
    }

    /** Signed balance per account from the first entry through this period. */
    private Map<String, Long> cumulativeBalances(String period) {
        Map<String, Long> out = new TreeMap<>();
        for (JournalEntryEntity e : entriesUpTo(period, true)) {
            accumulate(out, e);
        }
        return out;
    }

    private void accumulate(Map<String, Long> out, JournalEntryEntity e) {
        for (JournalEntryLineEntity l : lineRepo.findByJournalEntryIdOrderByLineNumberAsc(e.getId())) {
            ChartOfAccountEntity a = account(l.getAccountCode());
            long signed = "CREDIT".equals(normal(a))
                    ? nz(l.getCreditPaise()) - nz(l.getDebitPaise())
                    : nz(l.getDebitPaise()) - nz(l.getCreditPaise());
            out.merge(l.getAccountCode(), signed, Long::sum);
        }
    }

    /** Posted entries for the period, or everything up to and including it. */
    private List<JournalEntryEntity> entriesUpTo(String period, boolean cumulative) {
        if (!cumulative) {
            return journalRepo.findByPeriodAndStatusOrderByJournalDateAscIdAsc(
                    period, JournalService.POSTED);
        }
        return journalRepo.findByStatusOrderByJournalDateAscIdAsc(JournalService.POSTED).stream()
                .filter(e -> e.getPeriod() != null && e.getPeriod().compareTo(period) <= 0)
                .toList();
    }

    /** Net profit of every period before this one — the retained earnings. */
    private long profitBefore(String period) {
        long revenue = 0, expense = 0;
        for (JournalEntryEntity e : journalRepo
                .findByStatusOrderByJournalDateAscIdAsc(JournalService.POSTED)) {
            if (e.getPeriod() == null || e.getPeriod().compareTo(period) >= 0) continue;
            for (JournalEntryLineEntity l : lineRepo.findByJournalEntryIdOrderByLineNumberAsc(e.getId())) {
                ChartOfAccountEntity a = account(l.getAccountCode());
                if (a == null) continue;
                if ("REVENUE".equals(a.getAccountType())) {
                    revenue += nz(l.getCreditPaise()) - nz(l.getDebitPaise());
                } else if ("EXPENSE".equals(a.getAccountType())) {
                    expense += nz(l.getDebitPaise()) - nz(l.getCreditPaise());
                }
            }
        }
        return revenue - expense;
    }

    private List<StatementGroup> toGroups(Map<String, List<StatementLine>> grouped) {
        List<StatementGroup> out = new ArrayList<>();
        grouped.forEach((title, lines) ->
                out.add(new StatementGroup(title, lines,
                        lines.stream().mapToLong(StatementLine::amountPaise).sum())));
        return out;
    }

    private List<StatementLine> withPercent(List<StatementLine> lines, long revenue) {
        if (revenue <= 0) return lines;
        return lines.stream()
                .map(l -> new StatementLine(l.accountCode(), l.label(), l.amountPaise(),
                        (l.amountPaise() * 100.0) / revenue))
                .toList();
    }

    private StatementLine line(ChartOfAccountEntity a, long amount) {
        return new StatementLine(a.getCode(), a.getName(), amount, null);
    }

    /** A balance expressed on the account's normal side. */
    private long balanceOf(ChartOfAccountEntity a, long debit, long credit) {
        return "CREDIT".equals(normal(a)) ? credit - debit : debit - credit;
    }

    private final Map<String, ChartOfAccountEntity> cache = new HashMap<>();

    private ChartOfAccountEntity account(String code) {
        return cache.computeIfAbsent(code, c -> accountRepo.findByCode(c).orElse(null));
    }

    private static String name(ChartOfAccountEntity a) { return a == null ? "Unmapped" : a.getName(); }
    private static String type(ChartOfAccountEntity a) { return a == null ? "UNKNOWN" : a.getAccountType(); }
    private static String normal(ChartOfAccountEntity a) { return a == null ? "DEBIT" : a.getNormalBalance(); }
    private static String group(ChartOfAccountEntity a) {
        return a == null || a.getSubgroup() == null ? "Other" : a.getSubgroup();
    }

    private static Double margin(long part, long revenue) {
        if (revenue <= 0) return null;
        return (part * 100.0) / revenue;
    }

    private static long nz(Long v) { return v == null ? 0L : v; }
    private static String nvl(String v) { return v == null ? "" : v; }

    /** Accounting periods and their lock status. */
    public List<AccountingPeriodEntity> periods() {
        return periodRepo.findAllByOrderByPeriodDesc();
    }

    /** The period a date falls in, with its start and end. */
    public Optional<AccountingPeriodEntity> period(String period) {
        return periodRepo.findByPeriod(period);
    }

    public LocalDate periodStart(String period) { return YearMonth.parse(period).atDay(1); }
    public LocalDate periodEnd(String period) { return YearMonth.parse(period).atEndOfMonth(); }
}
