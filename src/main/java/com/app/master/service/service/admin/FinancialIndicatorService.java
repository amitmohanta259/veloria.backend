package com.app.master.service.service.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Financial ratios, derived from the general ledger.
 *
 * Every ratio is computed from the same journal entries the statements are built
 * from, so a ratio can never disagree with the profit and loss it came from.
 * Each one carries its own numerator and denominator, so the working is visible
 * rather than a bare number.
 *
 * <b>A ratio whose inputs do not exist returns null, not a number.</b> Cost of
 * goods sold is not posted, so inventory turnover and gross margin cannot be
 * computed; there is no interest expense, so interest coverage cannot be. Those
 * come back as {@code NOT_AVAILABLE} with the reason attached rather than as a
 * figure that would look measured.
 *
 * Ratios against a negative denominator are also refused. Return on equity
 * against negative equity produces a positive-looking number from a loss, which
 * is worse than no number at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FinancialIndicatorService {

    public static final String HEALTHY       = "HEALTHY";
    public static final String ADEQUATE      = "ADEQUATE";
    public static final String TIGHT         = "TIGHT";
    public static final String NOT_AVAILABLE = "NOT_AVAILABLE";
    public static final String NOT_MEANINGFUL = "NOT_MEANINGFUL";

    private final GeneralLedgerService ledger;

    /**
     * One ratio, with the figures behind it.
     *
     * {@code value} is null whenever the ratio cannot honestly be computed; the
     * status and note say which of the two reasons applies.
     */
    public record Indicator(String label, Double value, String unit, String formula,
                            String status, String note,
                            Long numeratorPaise, Long denominatorPaise,
                            String numeratorLabel, String denominatorLabel) {}

    public record IndicatorGroup(String title, List<Indicator> indicators) {}

    public record IndicatorReport(String period, List<IndicatorGroup> groups, List<String> notes) {}

    // ── Report ───────────────────────────────────────────────────────────────

    public IndicatorReport indicators(String period) {
        var pl = ledger.profitAndLoss(period);
        var bs = ledger.balanceSheet(period);

        long revenue     = pl.revenuePaise();
        long cogs        = pl.cogsPaise();
        long netProfit   = pl.netProfitPaise();
        long assets      = bs.totalAssetsPaise();
        long liabilities = bs.totalLiabilitiesPaise();
        long equity      = bs.totalEquityPaise();
        long inventory   = balanceOf(period, AccountingPostingService.INVENTORY);
        long receivables = balanceOf(period, AccountingPostingService.RECEIVABLE);
        long quickAssets = assets - inventory;
        int daysInPeriod = YearMonth.parse(period).lengthOfMonth();

        List<IndicatorGroup> groups = new ArrayList<>();

        // ── Profitability ────────────────────────────────────────────────────
        List<Indicator> profitability = new ArrayList<>();

        profitability.add(cogs == 0
                ? unavailable("Gross Margin", "%", "(Revenue − COGS) ÷ Revenue",
                        "Cost of goods sold is not posted, so this would read as 100% of revenue "
                        + "and mean nothing")
                : percentage("Gross Margin", revenue - cogs, revenue,
                        "(Revenue − COGS) ÷ Revenue", "Gross profit", "Revenue",
                        40.0, 20.0));

        profitability.add(percentage("Net Margin", netProfit, revenue,
                "Net profit ÷ Revenue", "Net profit", "Revenue", 10.0, 0.0));

        profitability.add(equity <= 0
                ? notMeaningful("Return on Equity", "%", "Net profit ÷ Equity",
                        "Equity is not positive, so this ratio would turn a loss into a "
                        + "positive-looking figure", netProfit, equity, "Net profit", "Equity")
                : percentage("Return on Equity", netProfit, equity,
                        "Net profit ÷ Equity", "Net profit", "Equity", 15.0, 5.0));

        profitability.add(ratio("Current Ratio", assets, liabilities,
                "Current assets ÷ Current liabilities", "Current assets", "Current liabilities",
                1.5, 1.0));

        profitability.add(ratio("Quick Ratio", quickAssets, liabilities,
                "(Current assets − Inventory) ÷ Current liabilities",
                "Quick assets", "Current liabilities", 1.0, 0.7));

        groups.add(new IndicatorGroup("Profitability and liquidity", profitability));

        // ── Activity ─────────────────────────────────────────────────────────
        List<Indicator> activity = new ArrayList<>();

        activity.add(cogs == 0 || inventory == 0
                ? unavailable("Inventory Turnover", "x", "COGS ÷ Average inventory",
                        cogs == 0
                            ? "Cost of goods sold is not posted, so turnover cannot be measured"
                            : "No inventory is carried in the ledger")
                : ratio("Inventory Turnover", cogs, inventory,
                        "COGS ÷ Average inventory", "COGS", "Inventory", 6.0, 4.0));

        activity.add(revenue <= 0
                ? notMeaningful("Days Sales Outstanding", "days",
                        "(Receivables ÷ Revenue) × days in period",
                        "Revenue is not positive for this period, so a collection period cannot "
                        + "be derived", receivables, revenue, "Receivables", "Revenue")
                : days("Days Sales Outstanding", receivables, revenue, daysInPeriod));

        groups.add(new IndicatorGroup("Activity", activity));

        // ── Leverage ─────────────────────────────────────────────────────────
        List<Indicator> leverage = new ArrayList<>();

        leverage.add(equity <= 0
                ? notMeaningful("Debt to Equity", "x", "Liabilities ÷ Equity",
                        "Equity is not positive, so the ratio has no readable sign",
                        liabilities, equity, "Liabilities", "Equity")
                : ratioInverted("Debt to Equity", liabilities, equity,
                        "Liabilities ÷ Equity", "Liabilities", "Equity", 1.0, 2.0));

        leverage.add(unavailable("Interest Coverage", "x", "Operating profit ÷ Interest expense",
                "No interest expense account carries activity, and there is no loan schedule to "
                + "compute one from"));

        groups.add(new IndicatorGroup("Leverage", leverage));

        return new IndicatorReport(period, groups, notes(pl.revenuePaise(), cogs, equity));
    }

    // ── Notes ────────────────────────────────────────────────────────────────

    private List<String> notes(long revenue, long cogs, long equity) {
        List<String> notes = new ArrayList<>();
        notes.add("Every ratio is computed from the general ledger — the same journal entries the "
                + "profit and loss and balance sheet are built from, so a ratio cannot disagree "
                + "with the statement it came from.");
        if (cogs == 0) {
            notes.add("Cost of goods sold is not posted: inventory is not valued and stock is not "
                    + "decremented on sale. Gross margin and inventory turnover therefore cannot "
                    + "be computed and are shown as unavailable rather than as 100%.");
        }
        if (equity <= 0) {
            notes.add("Equity is negative, so return on equity and debt to equity are marked not "
                    + "meaningful. Dividing by a negative denominator flips the sign and would "
                    + "present a loss as a positive return.");
        }
        notes.add("Opening balances have not been posted, so the ledger reflects only transactions "
                + "recorded since it began. Ratios built on the balance sheet understate the "
                + "position until those are entered.");
        return notes;
    }

    // ── Construction helpers ─────────────────────────────────────────────────

    private Indicator percentage(String label, long numerator, long denominator, String formula,
                                 String nLabel, String dLabel, double good, double fair) {
        if (denominator <= 0) {
            return notMeaningful(label, "%", formula,
                    "The denominator is not positive, so a percentage cannot be read",
                    numerator, denominator, nLabel, dLabel);
        }
        double v = round((numerator * 100.0) / denominator);
        return new Indicator(label, v, "%", formula, band(v, good, fair), null,
                numerator, denominator, nLabel, dLabel);
    }

    private Indicator ratio(String label, long numerator, long denominator, String formula,
                            String nLabel, String dLabel, double good, double fair) {
        if (denominator <= 0) {
            return notMeaningful(label, "x", formula,
                    "The denominator is not positive, so the ratio cannot be read",
                    numerator, denominator, nLabel, dLabel);
        }
        double v = round((double) numerator / denominator);
        return new Indicator(label, v, "x", formula, band(v, good, fair), null,
                numerator, denominator, nLabel, dLabel);
    }

    /** For ratios where a lower figure is the healthier one. */
    private Indicator ratioInverted(String label, long numerator, long denominator, String formula,
                                    String nLabel, String dLabel, double good, double fair) {
        if (denominator <= 0) {
            return notMeaningful(label, "x", formula, "The denominator is not positive",
                    numerator, denominator, nLabel, dLabel);
        }
        double v = round((double) numerator / denominator);
        String status = v <= good ? HEALTHY : v <= fair ? ADEQUATE : TIGHT;
        return new Indicator(label, v, "x", formula, status, null,
                numerator, denominator, nLabel, dLabel);
    }

    private Indicator days(String label, long receivables, long revenue, int daysInPeriod) {
        double v = round(((double) receivables / revenue) * daysInPeriod);
        String status = v <= 30 ? HEALTHY : v <= 60 ? ADEQUATE : TIGHT;
        return new Indicator(label, v, "days",
                "(Receivables ÷ Revenue) × days in period", status,
                "Days of sales still uncollected", receivables, revenue, "Receivables", "Revenue");
    }

    private Indicator unavailable(String label, String unit, String formula, String why) {
        return new Indicator(label, null, unit, formula, NOT_AVAILABLE, why, null, null, null, null);
    }

    private Indicator notMeaningful(String label, String unit, String formula, String why,
                                    long numerator, long denominator,
                                    String nLabel, String dLabel) {
        return new Indicator(label, null, unit, formula, NOT_MEANINGFUL, why,
                numerator, denominator, nLabel, dLabel);
    }

    private String band(double v, double good, double fair) {
        if (v >= good) return HEALTHY;
        if (v >= fair) return ADEQUATE;
        return TIGHT;
    }

    /** Cumulative balance of one account through the period, on its normal side. */
    private long balanceOf(String period, String accountCode) {
        return ledger.balanceSheet(period).assets().stream()
                .flatMap(g -> g.lines().stream())
                .filter(l -> accountCode.equals(l.accountCode()))
                .mapToLong(GeneralLedgerService.StatementLine::amountPaise)
                .findFirst()
                .orElse(0L);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
