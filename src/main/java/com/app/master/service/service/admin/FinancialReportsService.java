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
 * The reporting statements behind the Financial Reporting Hub.
 *
 * These are the reports that go beyond a single period's profit and loss —
 * position, ledger, liquidity, tax and operating cost. Each is built only from
 * records that exist.
 *
 * <b>Where a statement cannot be completed, it says so.</b> This system tracks
 * no bank or cash account and holds no opening balances, so a balance sheet
 * cannot be closed and cash-based ratios cannot be computed. Those figures are
 * reported as unavailable with the reason attached, never as a balancing plug —
 * a balance sheet that balances because the gap was filled in is worse than one
 * that openly does not.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FinancialReportsService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final GstMovementLedgerRepository movementRepo;
    private final ExpenseRepository expenseRepo;
    private final SalaryPaymentRepository salaryRepo;
    private final PurchaseInvoiceRepository purchaseInvoiceRepo;
    private final CustomerOrderRepository orderRepo;
    private final InventoryProductRepository productRepo;
    private final GstPaymentRepository gstPaymentRepo;
    private final GstTaxPeriodRepository taxPeriodRepo;

    // ── Shared shapes ────────────────────────────────────────────────────────

    /** A line on a statement. {@code amountPaise} is null when not derivable. */
    public record Line(String label, Long amountPaise, String note) {}

    public record Section(String title, List<Line> lines, Long subtotalPaise) {}

    // ═════════════════════ 1. Balance sheet / position ═══════════════════════

    public record BalanceSheet(String asOf,
                               List<Section> assets, List<Section> liabilities,
                               Long totalAssetsPaise, Long totalLiabilitiesPaise,
                               Long equityPaise, boolean balances,
                               List<String> gaps) {}

    /**
     * What the records show the business owns and owes.
     *
     * Deliberately not called a balance sheet in its own gaps: without a cash
     * account and opening balances it cannot be closed, so equity is reported
     * as null rather than derived as assets minus liabilities — that subtraction
     * would produce a number that looks like equity and is not.
     */
    public BalanceSheet balanceSheet(YearMonth ym) {
        String period = ym.toString();
        LocalDate asOf = ym.atEndOfMonth();
        List<String> gaps = new ArrayList<>();

        // ── Assets ───────────────────────────────────────────────────────────
        long inventory = productRepo.findAll().stream()
                .mapToLong(p -> nz(p.getInitialStock()) * nz(p.getPrice()))
                .sum();

        // Money owed by customers: dispatched but not yet settled. Returned
        // orders are excluded — that money is not coming.
        long receivables = orderRepo.findByArchiveFalseOrderByIdAsc().stream()
                .filter(o -> o.getOrderPlacedAt() != null)
                .filter(o -> !LocalDate.ofInstant(o.getOrderPlacedAt(), IST).isAfter(asOf))
                .filter(o -> !"RETURNED".equalsIgnoreCase(nvl(o.getStatus(), "")))
                .filter(o -> !"CANCELLED".equalsIgnoreCase(nvl(o.getStatus(), "")))
                .mapToLong(o -> nz(o.getTotalValue()))
                .sum();

        // Input credit already claimed is an asset: it reduces a future payment.
        long itcAvailable = movementRepo.findAll().stream()
                .filter(m -> "POSTED".equals(m.getStatus()) && "IN".equals(m.getDirection()))
                .filter(m -> m.getTaxPeriod() != null && m.getTaxPeriod().compareTo(period) <= 0)
                .mapToLong(m -> nz(m.getTotalTaxPaise()))
                .sum();

        List<Section> assets = List.of(
                new Section("Current assets", List.of(
                        new Line("Inventory at cost", inventory,
                                "Opening stock valued at cost price"),
                        new Line("Trade receivables", receivables,
                                "Orders placed and not returned"),
                        new Line("Input tax credit available", itcAvailable,
                                "Recoverable against future GST"),
                        new Line("Cash and bank", null,
                                "Not tracked — no bank or cash account exists in this system")
                ), inventory + receivables + itcAvailable));

        // ── Liabilities ──────────────────────────────────────────────────────
        long unpaidExpenses = expenseRepo.findAll().stream()
                .filter(e -> e.getExpenseDate() != null && !e.getExpenseDate().isAfter(asOf))
                .filter(e -> !"PAID".equalsIgnoreCase(nvl(e.getStatus(), "")))
                .mapToLong(e -> nz(e.getTotalPaise()))
                .sum();

        long unpaidPayroll = salaryRepo.findAll().stream()
                .filter(s -> s.getPaymentMonth() != null && s.getPaymentMonth().compareTo(period) <= 0)
                .filter(s -> !"PAID".equalsIgnoreCase(nvl(s.getStatus(), "")))
                .mapToLong(s -> nz(s.getTotalNetPaise()))
                .sum();

        long gstOwed = Math.max(0, netGstUpTo(period));

        List<Section> liabilities = List.of(
                new Section("Current liabilities", List.of(
                        new Line("Expenses payable", unpaidExpenses,
                                "Vouchers recorded and not yet paid"),
                        new Line("Salaries payable", unpaidPayroll,
                                "Payroll runs not yet marked paid"),
                        new Line("GST payable", gstOwed,
                                "Output tax less input credit, to date"),
                        new Line("Trade payables", null,
                                "Not tracked — vendor invoices carry no payment status")
                ), unpaidExpenses + unpaidPayroll + gstOwed));

        long totalAssets = inventory + receivables + itcAvailable;
        long totalLiabilities = unpaidExpenses + unpaidPayroll + gstOwed;

        gaps.add("This statement does not balance, and is not meant to: there is no bank or cash "
               + "account in the system and no opening balances, so equity cannot be derived. "
               + "Assets minus liabilities is reported as null rather than as a plug figure.");
        gaps.add("Inventory is valued at opening stock × cost price. Stock is not decremented on "
               + "sale, so this overstates what is actually held.");
        gaps.add("Trade receivables assume every non-returned order is still owed; there is no "
               + "settlement or payment-received record to net against it.");

        return new BalanceSheet(asOf.toString(), assets, liabilities,
                totalAssets, totalLiabilities, null, false, gaps);
    }

    // ═════════════════════ 2. Account ledger ═════════════════════════════════

    public record LedgerRow(String date, String reference, String category, String description,
                            Long debitPaise, Long creditPaise, long runningBalancePaise) {}

    public record AccountLedger(String period, List<LedgerRow> rows,
                                long totalDebitPaise, long totalCreditPaise,
                                long closingBalancePaise, List<String> gaps) {}

    /**
     * Every recorded movement as a running account, oldest first.
     *
     * Money in is a credit, money out a debit, and the balance runs from zero at
     * the start of the period — not from an opening balance, because none is
     * recorded. The closing figure is therefore the period's net movement, not
     * a bank balance.
     */
    public AccountLedger accountLedger(String period) {
        List<Object[]> raw = new ArrayList<>();

        for (CustomerOrderEntity o : orderRepo.findByArchiveFalseOrderByIdAsc()) {
            if (o.getOrderPlacedAt() == null) continue;
            LocalDate d = LocalDate.ofInstant(o.getOrderPlacedAt(), IST);
            raw.add(new Object[]{d, o.getOrderCode(), "Revenue",
                    "Sale — " + nvl(o.getCustomerName(), "customer order"),
                    nz(o.getTotalValue()), 0L});
        }
        for (ExpenseEntity e : expenseRepo.findAll()) {
            if (e.getExpenseDate() == null) continue;
            raw.add(new Object[]{e.getExpenseDate(), e.getVoucherNumber(), "Operating",
                    titleCase(nvl(e.getExpenseType(), "Expense"))
                        + (e.getSupplierName() != null ? " — " + e.getSupplierName() : ""),
                    0L, nz(e.getTotalPaise())});
        }
        for (SalaryPaymentEntity s : salaryRepo.findAll()) {
            if (s.getPaymentMonth() == null) continue;
            raw.add(new Object[]{YearMonth.parse(s.getPaymentMonth()).atEndOfMonth(),
                    s.getVoucherNumber(), "Personnel",
                    "Payroll — " + s.getEmployeeCount() + " staff",
                    0L, nz(s.getTotalNetPaise())});
        }
        for (PurchaseInvoiceEntity p : purchaseInvoiceRepo.findAll()) {
            if (p.getVendorInvoiceDate() == null) continue;
            raw.add(new Object[]{p.getVendorInvoiceDate(), p.getVendorInvoiceNumber(), "Purchases",
                    "Purchase — " + nvl(p.getVendorName(), nvl(p.getVendorGstin(), "vendor")),
                    0L, nz(p.getTotalInvoiceValue())});
        }
        for (GstPaymentEntity g : gstPaymentRepo.findAll()) {
            if (g.getPaymentDate() == null) continue;
            raw.add(new Object[]{g.getPaymentDate(), g.getPaymentReference(), "Tax",
                    "GST payment — " + nvl(g.getTaxPeriod(), ""),
                    0L, nz(g.getTotalPaise())});
        }

        List<LedgerRow> rows = new ArrayList<>();
        long balance = 0, debits = 0, credits = 0;

        List<Object[]> ordered = raw.stream()
                .filter(r -> period == null || period.isBlank()
                        || YearMonth.from((LocalDate) r[0]).toString().equals(period))
                .sorted(Comparator.comparing(r -> (LocalDate) r[0]))
                .toList();

        for (Object[] r : ordered) {
            long credit = (Long) r[4];      // money in
            long debit = (Long) r[5];       // money out
            balance += credit - debit;
            credits += credit;
            debits += debit;
            rows.add(new LedgerRow(r[0].toString(), (String) r[1], (String) r[2], (String) r[3],
                    debit == 0 ? null : debit, credit == 0 ? null : credit, balance));
        }

        return new AccountLedger(period, rows, debits, credits, balance, List.of(
                "The balance runs from zero at the start of the period. No opening balance is "
              + "recorded anywhere, so the closing figure is the period's net movement rather "
              + "than a bank balance.",
                "Amounts are document totals including GST, which is how they appear on the "
              + "documents themselves."));
    }

    // ═════════════════════ 3. Liquidity ══════════════════════════════════════

    public record Ratio(String label, Double value, String status, String basis, String note) {}

    public record Liquidity(String period, List<Ratio> ratios,
                            long currentAssetsPaise, long currentLiabilitiesPaise,
                            Long workingCapitalPaise, List<String> gaps) {}

    /**
     * Liquidity ratios, computed only where the inputs exist.
     *
     * The cash ratio needs a cash balance, which this system does not hold, so
     * it is returned with a null value and the reason rather than a number.
     */
    public Liquidity liquidity(YearMonth ym) {
        BalanceSheet bs = balanceSheet(ym);
        long ca = nz(bs.totalAssetsPaise());
        long cl = nz(bs.totalLiabilitiesPaise());

        // Quick assets exclude inventory: it cannot be turned into cash quickly.
        long inventory = productRepo.findAll().stream()
                .mapToLong(p -> nz(p.getInitialStock()) * nz(p.getPrice())).sum();
        long quick = ca - inventory;

        List<Ratio> ratios = List.of(
                new Ratio("Current ratio", ratio(ca, cl), band(ratio(ca, cl), 1.5, 1.0),
                        "Current assets ÷ current liabilities", "Standard: 1.5 or above"),
                new Ratio("Quick ratio", ratio(quick, cl), band(ratio(quick, cl), 1.0, 0.7),
                        "Current assets less inventory ÷ current liabilities",
                        "Excludes stock, which cannot be realised quickly"),
                new Ratio("Cash ratio", null, "NOT_AVAILABLE",
                        "Cash ÷ current liabilities",
                        "No bank or cash account is tracked, so this cannot be computed"),
                new Ratio("Working capital", null, cl == 0 ? "NO_LIABILITIES" : "COMPUTED",
                        "Current assets less current liabilities",
                        "Shown as an amount rather than a ratio"));

        return new Liquidity(ym.toString(), ratios, ca, cl, ca - cl, List.of(
                "Every ratio here inherits the balance sheet's limits: inventory is opening stock, "
              + "receivables assume nothing has been settled, and cash is absent entirely.",
                "Treat these as directional. They are not audited working-capital measures."));
    }

    // ═════════════════════ 4. GST and tax ════════════════════════════════════

    public record GstPeriodRow(String period, long outputPaise, long inputPaise,
                               long netPaise, long paidPaise, String status) {}

    public record GstReport(String period, long outputPaise, long inputPaise, long netPaise,
                            long paidPaise, long outstandingPaise,
                            List<GstPeriodRow> periods, List<String> gaps) {}

    /** The tax position by period, taken from the GST ledger and payments. */
    public GstReport gstReport(YearMonth ym) {
        List<GstPeriodRow> rows = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            String p = ym.minusMonths(i).toString();
            long out = 0, in = 0;
            for (GstMovementLedgerEntity m : movementRepo.findByTaxPeriodOrderByCreatedAtDesc(p)) {
                if (!"POSTED".equals(m.getStatus())) continue;
                if ("OUT".equals(m.getDirection()) || "ADJUSTMENT".equals(m.getDirection())) {
                    out += nz(m.getTotalTaxPaise());
                } else if ("IN".equals(m.getDirection())) {
                    in += nz(m.getTotalTaxPaise());
                }
            }
            long paid = gstPaymentRepo.findAll().stream()
                    .filter(g -> p.equals(g.getTaxPeriod()))
                    .mapToLong(g -> nz(g.getTotalPaise())).sum();
            String status = taxPeriodRepo.findAll().stream()
                    .filter(t -> p.equals(t.getTaxPeriod()))
                    .map(GstTaxPeriodEntity::getStatus)
                    .findFirst().orElse("OPEN");
            rows.add(new GstPeriodRow(p, out, in, out - in, paid, status));
        }

        GstPeriodRow current = rows.get(rows.size() - 1);
        long outstanding = current.netPaise() - current.paidPaise();

        return new GstReport(ym.toString(), current.outputPaise(), current.inputPaise(),
                current.netPaise(), current.paidPaise(), outstanding, rows, List.of(
                        "Figures come from the GST movement ledger, which is the same source the "
                      + "returns are prepared from — the tax module and this report cannot disagree.",
                        "A negative net figure means credits exceeded output tax for the period, "
                      + "which carries forward rather than becoming a refund automatically."));
    }

    // ═════════════════════ 5. Operating expenses ═════════════════════════════

    public record OpexLine(String category, long netPaise, long taxPaise, long grossPaise,
                           int count, String status) {}

    public record OpexReport(String period, List<OpexLine> lines,
                             long totalNetPaise, long totalTaxPaise, long totalGrossPaise,
                             long payrollPaise, int headcount, List<String> gaps) {}

    /** Operating cost for a period, grouped by the expense type on each voucher. */
    public OpexReport opexReport(YearMonth ym) {
        Map<String, long[]> byType = new TreeMap<>();   // net, tax, gross, count
        Map<String, String> statusByType = new HashMap<>();

        for (ExpenseEntity e : expenseRepo.findAll()) {
            if (e.getExpenseDate() == null || !YearMonth.from(e.getExpenseDate()).equals(ym)) continue;
            String type = titleCase(nvl(e.getExpenseType(), "Other"));
            long[] agg = byType.computeIfAbsent(type, k -> new long[4]);
            agg[0] += nz(e.getSubtotalPaise());
            agg[1] += nz(e.getTaxPaise());
            agg[2] += nz(e.getTotalPaise());
            agg[3] += 1;
            statusByType.merge(type, nvl(e.getStatus(), "—"),
                    (a, b) -> a.equals(b) ? a : "MIXED");
        }

        List<OpexLine> lines = new ArrayList<>();
        byType.forEach((type, a) -> lines.add(new OpexLine(type, a[0], a[1], a[2],
                (int) a[3], statusByType.getOrDefault(type, "—"))));

        long gross = 0, payroll = 0;
        int headcount = 0;
        for (SalaryPaymentEntity s : salaryRepo.findAll()) {
            if (!ym.toString().equals(s.getPaymentMonth())) continue;
            payroll += nz(s.getTotalGrossPaise()) + nz(s.getTotalEmployerContributionPaise());
            headcount += s.getEmployeeCount() == null ? 0 : s.getEmployeeCount();
        }

        long net = lines.stream().mapToLong(OpexLine::netPaise).sum();
        long tax = lines.stream().mapToLong(OpexLine::taxPaise).sum();
        gross = lines.stream().mapToLong(OpexLine::grossPaise).sum();

        return new OpexReport(ym.toString(), lines, net, tax, gross, payroll, headcount, List.of(
                "Expense figures are shown net and gross. The tax portion is reclaimable as input "
              + "credit where the vendor is registered, so only the net amount is a real cost.",
                "Payroll is listed separately from expense vouchers because it is not raised as one."));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private long netGstUpTo(String period) {
        long out = 0, in = 0;
        for (GstMovementLedgerEntity m : movementRepo.findAll()) {
            if (!"POSTED".equals(m.getStatus())) continue;
            if (m.getTaxPeriod() == null || m.getTaxPeriod().compareTo(period) > 0) continue;
            if ("OUT".equals(m.getDirection()) || "ADJUSTMENT".equals(m.getDirection())) {
                out += nz(m.getTotalTaxPaise());
            } else if ("IN".equals(m.getDirection())) {
                in += nz(m.getTotalTaxPaise());
            }
        }
        long paid = gstPaymentRepo.findAll().stream().mapToLong(g -> nz(g.getTotalPaise())).sum();
        return out - in - paid;
    }

    private static Double ratio(long a, long b) {
        if (b <= 0) return null;
        return Math.round((a * 100.0) / b) / 100.0;
    }

    private static String band(Double v, double good, double fair) {
        if (v == null) return "NOT_AVAILABLE";
        if (v >= good) return "HEALTHY";
        if (v >= fair) return "ADEQUATE";
        return "TIGHT";
    }

    private static long nz(Long v) { return v == null ? 0L : v; }

    private static String nvl(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String titleCase(String raw) {
        String t = raw.replace('_', ' ').toLowerCase(Locale.ROOT).trim();
        return t.isEmpty() ? "Other" : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
