package com.app.master.service.service.admin;

import com.app.master.service.core.entity.*;
import com.app.master.service.repository.admin.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * The financial position, assembled from what the business actually recorded.
 *
 * Every figure here is derived from a document that exists — an order, an
 * invoice, an expense voucher, a payroll run. Nothing is estimated, and where a
 * figure cannot be derived it is reported as zero with the reason surfaced
 * rather than filled in with something plausible.
 *
 * Two decisions worth stating, because they change what the numbers mean:
 *
 * 1. <b>Revenue excludes GST.</b> Tax collected from a customer is a liability
 *    owed to the government, never income. Revenue is therefore the taxable
 *    value, not the invoice total.
 *
 * 2. <b>Revenue comes from the GST movement ledger.</b> That ledger already
 *    resolves which document governs a supply — an issued invoice supersedes the
 *    order behind it — and already carries returns as negative adjustments.
 *    Re-deriving revenue from orders would duplicate that logic and drift from it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FinancialsService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final GstMovementLedgerRepository movementRepo;
    private final ExpenseRepository expenseRepo;
    private final SalaryPaymentRepository salaryRepo;
    private final PurchaseInvoiceRepository purchaseInvoiceRepo;
    private final CustomerOrderRepository orderRepo;

    // ── Shapes ───────────────────────────────────────────────────────────────

    /** One headline figure, with the movement against the previous period. */
    public record Metric(String label, long valuePaise, Double changePercent,
                         String note, String basis) {}

    /**
     * A period's result.
     *
     * There is deliberately no gross profit or gross margin here. Gross profit
     * needs cost of goods <i>sold</i>, and that needs opening and closing
     * inventory valuation, which this system does not track. What can be known
     * is what was purchased in the period, so that is what is reported —
     * {@code purchasesPaise}, not a cost of sales dressed up as one.
     */
    public record ProfitAndLoss(
            String period,
            long revenuePaise,
            long purchasesPaise,
            long operatingExpensePaise,
            long payrollPaise,
            long netResultPaise,
            long gstCollectedPaise,
            long gstInputCreditPaise,
            long netGstLiabilityPaise,
            Double netMarginPercent) {}

    /** A month on the trend line. */
    public record MonthPoint(String period, long revenuePaise, long expensePaise,
                             long netProfitPaise) {}

    /** One real transaction, from whichever ledger it belongs to. */
    public record LedgerEntry(String reference, String description, String category,
                              String status, LocalDate date, long amountPaise,
                              String direction) {}

    public record Summary(List<Metric> metrics, ProfitAndLoss profitAndLoss,
                          List<String> gaps) {}

    // ── Summary ──────────────────────────────────────────────────────────────

    /** Headline metrics for a month, each with its previous-month movement. */
    public Summary summary(String period) {
        YearMonth ym = period == null || period.isBlank()
                ? YearMonth.now(IST) : YearMonth.parse(period);
        ProfitAndLoss now = profitAndLoss(ym);
        ProfitAndLoss prev = profitAndLoss(ym.minusMonths(1));

        List<Metric> metrics = List.of(
                new Metric("Total Revenue", now.revenuePaise(),
                        change(now.revenuePaise(), prev.revenuePaise()),
                        "Taxable value of supplies, excluding GST",
                        "GST movement ledger"),
                new Metric("Net Result", now.netResultPaise(),
                        change(now.netResultPaise(), prev.netResultPaise()),
                        now.netMarginPercent() == null
                                ? "No margin — revenue is not positive this period"
                                : String.format("%.1f%% margin", now.netMarginPercent()),
                        "Revenue less purchases, expenses and payroll"),
                new Metric("GST Liability", now.netGstLiabilityPaise(),
                        change(now.netGstLiabilityPaise(), prev.netGstLiabilityPaise()),
                        "Output tax less input credit",
                        "GST movement ledger"),
                new Metric("Operating Expenses",
                        now.operatingExpensePaise() + now.payrollPaise(),
                        change(now.operatingExpensePaise() + now.payrollPaise(),
                               prev.operatingExpensePaise() + prev.payrollPaise()),
                        "Expense vouchers and payroll",
                        "Expenses and salary payments"));

        return new Summary(metrics, now, gaps(ym, now));
    }

    // ── Profit and loss ──────────────────────────────────────────────────────

    public ProfitAndLoss profitAndLoss(YearMonth ym) {
        String period = ym.toString();

        long revenue = 0, gstCollected = 0, gstInput = 0;
        for (GstMovementLedgerEntity m : movementRepo.findByTaxPeriodOrderByCreatedAtDesc(period)) {
            if (!"POSTED".equals(m.getStatus())) continue;   // superseded rows are retired
            String dir = m.getDirection();
            if ("OUT".equals(dir) || "ADJUSTMENT".equals(dir)) {
                revenue += nz(m.getTaxableValuePaise());
                gstCollected += nz(m.getTotalTaxPaise());
            } else if ("IN".equals(dir)) {
                gstInput += nz(m.getTotalTaxPaise());
            }
        }

        // Purchases are taken net of GST: the tax on a purchase is reclaimable
        // as input credit, so charging it to the P&L would count it twice.
        long purchases = purchaseInvoiceRepo.findAll().stream()
                .filter(p -> period.equals(p.getTaxPeriod()))
                .mapToLong(p -> nz(p.getTaxableValue()))
                .sum();

        long expenses = expenseRepo.findAll().stream()
                .filter(e -> e.getExpenseDate() != null
                        && YearMonth.from(e.getExpenseDate()).equals(ym))
                .mapToLong(e -> nz(e.getSubtotalPaise()))
                .sum();

        // Payroll cost is what the employer bears: gross pay plus the employer's
        // own contributions. Employee deductions are already inside gross.
        long payroll = salaryRepo.findAll().stream()
                .filter(s -> period.equals(s.getPaymentMonth()))
                .mapToLong(s -> nz(s.getTotalGrossPaise())
                        + nz(s.getTotalEmployerContributionPaise()))
                .sum();

        long netResult = revenue - purchases - expenses - payroll;

        return new ProfitAndLoss(period, revenue, purchases,
                expenses, payroll, netResult,
                gstCollected, gstInput, gstCollected - gstInput,
                margin(netResult, revenue));
    }

    /** The trend behind the chart — real months, not a drawn curve. */
    public List<MonthPoint> trend(int months) {
        int n = Math.max(1, Math.min(months, 24));
        YearMonth end = YearMonth.now(IST);
        List<MonthPoint> out = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            ProfitAndLoss p = profitAndLoss(end.minusMonths(i));
            out.add(new MonthPoint(p.period(), p.revenuePaise(),
                    p.purchasesPaise() + p.operatingExpensePaise() + p.payrollPaise(),
                    p.netResultPaise()));
        }
        return out;
    }

    // ── Ledger ───────────────────────────────────────────────────────────────

    /**
     * Every real money movement, newest first.
     *
     * Drawn from the documents themselves rather than a separate ledger table,
     * so an entry here always corresponds to something a person can open.
     */
    public List<LedgerEntry> ledger(String period, String category) {
        List<LedgerEntry> entries = new ArrayList<>();

        for (CustomerOrderEntity o : orderRepo.findByArchiveFalseOrderByIdAsc()) {
            if (o.getOrderPlacedAt() == null) continue;
            LocalDate d = LocalDate.ofInstant(o.getOrderPlacedAt(), IST);
            entries.add(new LedgerEntry(o.getOrderCode(),
                    "Sale — " + nvl(o.getCustomerName(), "customer order"),
                    "REVENUE", nvl(o.getStatus(), "—"), d, nz(o.getTaxableValue()), "IN"));
        }

        for (ExpenseEntity e : expenseRepo.findAll()) {
            if (e.getExpenseDate() == null) continue;
            entries.add(new LedgerEntry(e.getVoucherNumber(),
                    nvl(e.getExpenseType(), "Expense").replace('_', ' ')
                        + (e.getSupplierName() != null ? " — " + e.getSupplierName() : ""),
                    "EXPENSE", nvl(e.getStatus(), "—"), e.getExpenseDate(),
                    -nz(e.getSubtotalPaise()), "OUT"));
        }

        for (SalaryPaymentEntity s : salaryRepo.findAll()) {
            if (s.getPaymentMonth() == null) continue;
            entries.add(new LedgerEntry(s.getVoucherNumber(),
                    "Payroll — " + s.getEmployeeCount() + " staff",
                    "PAYROLL", nvl(s.getStatus(), "—"),
                    YearMonth.parse(s.getPaymentMonth()).atEndOfMonth(),
                    -(nz(s.getTotalGrossPaise()) + nz(s.getTotalEmployerContributionPaise())),
                    "OUT"));
        }

        for (PurchaseInvoiceEntity p : purchaseInvoiceRepo.findAll()) {
            if (p.getVendorInvoiceDate() == null) continue;
            entries.add(new LedgerEntry(p.getVendorInvoiceNumber(),
                    "Purchase — " + nvl(p.getVendorName(), p.getVendorGstin()),
                    "PURCHASE", nvl(p.getStatus(), "—"), p.getVendorInvoiceDate(),
                    -nz(p.getTaxableValue()), "OUT"));
        }

        return entries.stream()
                .filter(e -> period == null || period.isBlank()
                        || YearMonth.from(e.date()).toString().equals(period))
                .filter(e -> category == null || category.isBlank()
                        || category.equalsIgnoreCase(e.category()))
                .sorted(Comparator.comparing(LedgerEntry::date).reversed())
                .toList();
    }

    // ── Gaps ─────────────────────────────────────────────────────────────────

    /**
     * What this period cannot account for.
     *
     * Reported alongside the figures rather than left for someone to discover:
     * a zero that means "nothing recorded" is very different from a zero that
     * means "nothing happened".
     */
    private List<String> gaps(YearMonth ym, ProfitAndLoss p) {
        List<String> gaps = new ArrayList<>();
        if (p.revenuePaise() == 0) {
            gaps.add("No posted sales in " + ym + ", so revenue is zero rather than unmeasured.");
        }
        if (p.revenuePaise() < 0) {
            gaps.add("Revenue is negative for " + ym + " because returns and credit notes in this "
                   + "period exceed new sales. The figure is correct; the sales they reverse were "
                   + "recognised in an earlier period.");
        }
        if (p.purchasesPaise() == 0) {
            gaps.add("No purchase invoices recorded for " + ym + ", so nothing is charged against "
                   + "revenue for stock.");
        }
        gaps.add("Cost of goods sold is not computed: inventory is not valued, so purchases are "
               + "charged in full to the period they were invoiced in. The net result is therefore "
               + "not a gross margin.");
        if (p.payrollPaise() == 0) {
            gaps.add("No payroll run recorded for " + ym + ".");
        }
        long unpaidExpenses = expenseRepo.findAll().stream()
                .filter(e -> e.getExpenseDate() != null && YearMonth.from(e.getExpenseDate()).equals(ym))
                .filter(e -> !"PAID".equalsIgnoreCase(nvl(e.getStatus(), "")))
                .count();
        if (unpaidExpenses > 0) {
            gaps.add(unpaidExpenses + " expense voucher(s) in " + ym + " are not yet marked paid; "
                   + "they are counted as incurred, which is accrual rather than cash basis.");
        }
        return gaps;
    }


    // ── Profit and loss statement ────────────────────────────────────────────

    /** One line of the statement. Costs carry a negative amount. */
    public record StatementLine(String label, long amountPaise,
                                Double percentOfRevenue, String emphasis) {}

    public record StatementSection(String title, List<StatementLine> lines) {}

    public record ProfitLossStatement(String period, long revenuePaise,
                                      List<StatementSection> sections,
                                      StatementLine netResult, List<String> notes) {}

    /**
     * The profit and loss statement, broken into the lines the underlying
     * records actually support.
     *
     * Every line is a real aggregate: revenue split by the movement type that
     * produced it, expenses grouped by their own expense type, payroll split
     * into what the employer pays and what it contributes. Nothing is
     * apportioned or estimated, and a section with no records is omitted rather
     * than shown as a zero that looks like a measurement.
     */
    public ProfitLossStatement profitAndLossStatement(YearMonth ym) {
        String period = ym.toString();
        List<StatementSection> sections = new ArrayList<>();

        // ── I. Revenue, by what produced it ──────────────────────────────────
        Map<String, Long> revenueByType = new LinkedHashMap<>();
        for (GstMovementLedgerEntity m : movementRepo.findByTaxPeriodOrderByCreatedAtDesc(period)) {
            if (!"POSTED".equals(m.getStatus())) continue;
            String dir = m.getDirection();
            if (!"OUT".equals(dir) && !"ADJUSTMENT".equals(dir)) continue;
            revenueByType.merge(revenueLabel(m), nz(m.getTaxableValuePaise()), Long::sum);
        }
        long revenue = revenueByType.values().stream().mapToLong(Long::longValue).sum();

        List<StatementLine> revenueLines = new ArrayList<>();
        revenueByType.forEach((label, amount) ->
                revenueLines.add(new StatementLine(label, amount, pct(amount, revenue), "LINE")));
        revenueLines.add(new StatementLine("Net revenue", revenue, pct(revenue, revenue), "SUBTOTAL"));
        sections.add(new StatementSection("I. Revenue", revenueLines));

        // ── II. Purchases, by vendor ─────────────────────────────────────────
        Map<String, Long> purchasesByVendor = new LinkedHashMap<>();
        for (PurchaseInvoiceEntity p : purchaseInvoiceRepo.findAll()) {
            if (!period.equals(p.getTaxPeriod())) continue;
            purchasesByVendor.merge(
                    nvl(p.getVendorName(), nvl(p.getVendorGstin(), "Vendor")),
                    nz(p.getTaxableValue()), Long::sum);
        }
        long purchases = purchasesByVendor.values().stream().mapToLong(Long::longValue).sum();
        if (!purchasesByVendor.isEmpty()) {
            List<StatementLine> lines = new ArrayList<>();
            purchasesByVendor.forEach((v, a) ->
                    lines.add(new StatementLine(v, -a, pct(a, revenue), "LINE")));
            lines.add(new StatementLine("Total purchases", -purchases, pct(purchases, revenue), "SUBTOTAL"));
            sections.add(new StatementSection("II. Purchases", lines));
        }

        // ── III. Operating expenses, by their own type ───────────────────────
        Map<String, Long> byType = new TreeMap<>();
        for (ExpenseEntity e : expenseRepo.findAll()) {
            if (e.getExpenseDate() == null || !YearMonth.from(e.getExpenseDate()).equals(ym)) continue;
            byType.merge(titleCase(nvl(e.getExpenseType(), "Other")), nz(e.getSubtotalPaise()), Long::sum);
        }
        long expenses = byType.values().stream().mapToLong(Long::longValue).sum();
        if (!byType.isEmpty()) {
            List<StatementLine> lines = new ArrayList<>();
            byType.forEach((t, a) -> lines.add(new StatementLine(t, -a, pct(a, revenue), "LINE")));
            lines.add(new StatementLine("Total operating expenses", -expenses, pct(expenses, revenue), "SUBTOTAL"));
            sections.add(new StatementSection("III. Operating expenses", lines));
        }

        // ── IV. Payroll, split by who bears what ─────────────────────────────
        long gross = 0, employer = 0;
        int headcount = 0;
        for (SalaryPaymentEntity sp : salaryRepo.findAll()) {
            if (!period.equals(sp.getPaymentMonth())) continue;
            gross += nz(sp.getTotalGrossPaise());
            employer += nz(sp.getTotalEmployerContributionPaise());
            headcount += sp.getEmployeeCount() == null ? 0 : sp.getEmployeeCount();
        }
        long payroll = gross + employer;
        if (payroll != 0) {
            List<StatementLine> lines = new ArrayList<>();
            lines.add(new StatementLine("Gross salaries — " + headcount + " staff", -gross,
                    pct(gross, revenue), "LINE"));
            lines.add(new StatementLine("Employer PF and ESI", -employer, pct(employer, revenue), "LINE"));
            lines.add(new StatementLine("Total payroll cost", -payroll, pct(payroll, revenue), "SUBTOTAL"));
            sections.add(new StatementSection("IV. Payroll", lines));
        }

        long net = revenue - purchases - expenses - payroll;
        StatementLine netResult = new StatementLine("Net result for period", net,
                pct(net, revenue), "TOTAL");

        return new ProfitLossStatement(period, revenue, sections, netResult,
                gaps(ym, profitAndLoss(ym)));
    }

    /** Reads a movement as the revenue line it belongs on. */
    private static String revenueLabel(GstMovementLedgerEntity m) {
        String type = nvl(m.getMovementType(), "");
        return switch (type) {
            case "SALE_OUTPUT" -> "Sales";
            case "DEBIT_NOTE" -> "Debit notes — additional charges";
            case "SALES_CREDIT_NOTE", "CUSTOMER_RETURN_OUTPUT_ADJUSTMENT" ->
                    "Less: customer returns";
            case "OTHER_ADJUSTMENT" -> "Invoice cancellations and amendments";
            default -> titleCase(type.isBlank() ? "Other" : type);
        };
    }

    private static Double pct(long part, long revenue) {
        if (revenue <= 0) return null;
        return (part * 100.0) / revenue;
    }

    private static String titleCase(String raw) {
        String t = raw.replace('_', ' ').toLowerCase(Locale.ROOT).trim();
        return t.isEmpty() ? "Other" : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Double change(long now, long previous) {
        if (previous == 0) return null;            // no baseline; a percentage would be invented
        return ((now - previous) * 100.0) / Math.abs(previous);
    }

    private static Double margin(long part, long revenue) {
        // A margin needs positive revenue to divide by. Against zero it is
        // undefined; against negative revenue — a period of net returns — the
        // sign flips and the number reads as the opposite of what happened.
        if (revenue <= 0) return null;
        return (part * 100.0) / revenue;
    }

    private static long nz(Long v) { return v == null ? 0L : v; }

    private static String nvl(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
