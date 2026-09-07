package com.app.master.service.service.admin;

import com.app.master.service.core.entity.*;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.repository.admin.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.app.master.service.service.admin.JournalService.Posting.credit;
import static com.app.master.service.service.admin.JournalService.Posting.debit;

/**
 * Turns business transactions into balanced journal entries.
 *
 * This is the single place that knows which accounts a sale, an expense, a
 * payroll run or a purchase touches. Every rule here produces an entry whose
 * debits equal its credits by construction — the tax split comes from the
 * transaction, never from an assumed rate.
 *
 * Posting is idempotent per source record, so {@link #backfill} can be run
 * repeatedly and existing records are posted once.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountingPostingService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    // Chart of accounts codes, named so a posting rule reads as accounting.
    public static final String CASH              = "1010";
    public static final String BANK              = "1020";
    public static final String RECEIVABLE        = "1100";
    public static final String INVENTORY         = "1200";
    public static final String INPUT_GST         = "1300";
    public static final String PAYABLE           = "2010";
    public static final String OUTPUT_CGST       = "2100";
    public static final String OUTPUT_SGST       = "2110";
    public static final String OUTPUT_IGST       = "2120";
    public static final String GST_PAYABLE       = "2200";
    public static final String SALARY_PAYABLE    = "2300";
    public static final String SALES             = "4010";
    public static final String COGS              = "5010";
    public static final String MARKETING         = "5100";
    public static final String PAYROLL           = "5200";
    public static final String RENT              = "5300";
    public static final String UTILITIES         = "5400";
    public static final String OTHER_EXPENSE     = "5900";

    private final JournalService journal;
    private final CustomerOrderRepository orderRepo;
    private final SalesInvoiceRepository salesInvoiceRepo;
    private final ExpenseRepository expenseRepo;
    private final SalaryPaymentRepository salaryRepo;
    private final PurchaseInvoiceRepository purchaseRepo;
    private final GstPaymentRepository gstPaymentRepo;

    // ── Sales ────────────────────────────────────────────────────────────────

    /**
     * A sale.
     *
     * <pre>
     *   Dr Accounts Receivable   invoice total
     *      Cr Sales                          taxable value
     *      Cr Output CGST / SGST / IGST      as the transaction states
     * </pre>
     *
     * The tax heads come from the order's own amounts, so an intra-state supply
     * credits CGST and SGST and an inter-state one credits IGST — nothing here
     * decides the rate.
     */
    @Transactional(rollbackFor = Exception.class)
    public JournalEntryEntity postSale(CustomerOrderEntity order) throws VeloriaException {
        // Where an invoice has been issued it is the statutory document for the
        // supply and its figures were computed by the current rounding authority.
        // The order's own columns may predate that policy — some were produced by
        // an earlier implementation that truncated rather than rounding HALF_UP —
        // so taking the invoice keeps the books and the GST subledger on one
        // policy rather than reconciling a difference after the fact.
        // The earliest non-draft invoice is the document that governed this
        // supply when it happened. A later cancellation or amendment is a
        // separate event in its own period and needs its own journal — it is not
        // a reason to fall back to the order's pre-policy figures.
        SalesInvoiceEntity invoice = salesInvoiceRepo
                .findByOrderCodeOrderByIdDesc(order.getOrderCode()).stream()
                .filter(i -> !"DRAFT".equals(i.getStatus()))
                .min(java.util.Comparator.comparing(SalesInvoiceEntity::getId))
                .orElse(null);

        long taxable = invoice != null ? nz(invoice.getTaxableValue()) : nz(order.getTaxableValue());
        long cgst    = invoice != null ? nz(invoice.getCgstAmount())   : nz(order.getCgstAmount());
        long sgst    = invoice != null ? nz(invoice.getSgstAmount())   : nz(order.getSgstAmount());
        long igst    = invoice != null ? nz(invoice.getIgstAmount())   : nz(order.getIgstAmount());
        long total = taxable + cgst + sgst + igst;
        if (total == 0) return null;

        String basis = invoice != null ? invoice.getInvoiceNumber() : order.getOrderCode();

        List<JournalService.Posting> p = new ArrayList<>();
        p.add(debit(RECEIVABLE, total, "Sale " + basis));
        p.add(credit(SALES, taxable, "Revenue, net of GST"));
        if (cgst > 0) p.add(credit(OUTPUT_CGST, cgst, "Output CGST"));
        if (sgst > 0) p.add(credit(OUTPUT_SGST, sgst, "Output SGST"));
        if (igst > 0) p.add(credit(OUTPUT_IGST, igst, "Output IGST"));

        return journal.post(new JournalService.Draft(
                dateOf(order.getOrderPlacedAt()), order.getOrderCode(), "SALE", order.getId(),
                "Sale to " + nvl(order.getCustomerName(), "customer"), p));
    }

    // ── Expenses ─────────────────────────────────────────────────────────────

    /**
     * An expense voucher.
     *
     * <pre>
     *   Dr Expense account   net of tax
     *   Dr Input GST         where the tax is creditable
     *      Cr Accounts Payable            gross
     * </pre>
     *
     * Where no vendor GSTIN is recorded the tax is not creditable, so it is
     * charged to the expense instead of sitting in Input GST as a receivable
     * that can never be claimed.
     */
    @Transactional(rollbackFor = Exception.class)
    public JournalEntryEntity postExpense(ExpenseEntity expense) throws VeloriaException {
        long net = nz(expense.getSubtotalPaise());
        long tax = nz(expense.getTaxPaise());
        long gross = nz(expense.getTotalPaise());
        if (gross == 0) return null;

        boolean creditable = expense.getSupplierGstin() != null
                && !expense.getSupplierGstin().isBlank();

        String account = expenseAccount(expense.getExpenseType());
        List<JournalService.Posting> p = new ArrayList<>();
        if (creditable) {
            p.add(debit(account, net, titleOf(expense)));
            if (tax > 0) p.add(debit(INPUT_GST, tax, "Creditable input GST"));
        } else {
            // Not creditable: the tax is part of the cost, not a receivable.
            p.add(debit(account, net + tax, titleOf(expense) + " (tax not creditable)"));
        }
        p.add(credit(PAYABLE, gross, "Payable to " + nvl(expense.getSupplierName(), "supplier")));

        return journal.post(new JournalService.Draft(
                expense.getExpenseDate(), expense.getVoucherNumber(), "EXPENSE", expense.getId(),
                titleOf(expense), p));
    }

    // ── Payroll ──────────────────────────────────────────────────────────────

    /**
     * A payroll run.
     *
     * <pre>
     *   Dr Payroll expense   gross pay + employer contributions
     *      Cr Salary Payable                          the same
     * </pre>
     *
     * The cost to the business is gross pay plus what the employer contributes;
     * employee deductions are already inside gross and are not a separate cost.
     */
    @Transactional(rollbackFor = Exception.class)
    public JournalEntryEntity postPayroll(SalaryPaymentEntity salary) throws VeloriaException {
        long gross = nz(salary.getTotalGrossPaise());
        long employer = nz(salary.getTotalEmployerContributionPaise());
        long cost = gross + employer;
        if (cost == 0) return null;

        LocalDate date = YearMonth.parse(salary.getPaymentMonth()).atEndOfMonth();

        return journal.post(new JournalService.Draft(
                date, salary.getVoucherNumber(), "PAYROLL", salary.getId(),
                "Payroll for " + salary.getPaymentMonth() + " — "
                        + salary.getEmployeeCount() + " staff",
                List.of(
                        debit(PAYROLL, cost, "Gross pay and employer contributions"),
                        credit(SALARY_PAYABLE, cost, "Owed to staff and authorities"))));
    }

    // ── Purchases ────────────────────────────────────────────────────────────

    /**
     * A vendor invoice.
     *
     * <pre>
     *   Dr Inventory   taxable value
     *   Dr Input GST   the tax the vendor charged
     *      Cr Accounts Payable        invoice total
     * </pre>
     */
    @Transactional(rollbackFor = Exception.class)
    public JournalEntryEntity postPurchase(PurchaseInvoiceEntity invoice) throws VeloriaException {
        long taxable = nz(invoice.getTaxableValue());
        long tax = nz(invoice.getTotalTax());
        long total = taxable + tax;
        if (total == 0) return null;

        List<JournalService.Posting> p = new ArrayList<>();
        p.add(debit(INVENTORY, taxable, "Stock purchased"));
        if (tax > 0) p.add(debit(INPUT_GST, tax, "Input GST charged by vendor"));
        p.add(credit(PAYABLE, total, "Payable to " + nvl(invoice.getVendorName(), "vendor")));

        return journal.post(new JournalService.Draft(
                invoice.getVendorInvoiceDate(), invoice.getVendorInvoiceNumber(),
                "PURCHASE", invoice.getId(),
                "Purchase from " + nvl(invoice.getVendorName(), invoice.getVendorGstin()), p));
    }

    // ── GST payment ──────────────────────────────────────────────────────────

    /**
     * A payment of tax to the government.
     *
     * <pre>
     *   Dr GST Payable   amount paid
     *      Cr Bank                    amount paid
     * </pre>
     */
    @Transactional(rollbackFor = Exception.class)
    public JournalEntryEntity postGstPayment(GstPaymentEntity payment) throws VeloriaException {
        long amount = nz(payment.getTotalPaise());
        if (amount == 0) return null;

        return journal.post(new JournalService.Draft(
                payment.getPaymentDate(), payment.getPaymentReference(),
                "GST_PAYMENT", payment.getId(),
                "GST paid for " + nvl(payment.getTaxPeriod(), ""),
                List.of(
                        debit(GST_PAYABLE, amount, "Settles output tax"),
                        credit(BANK, amount, "Paid from bank"))));
    }

    // ── Backfill ─────────────────────────────────────────────────────────────

    /**
     * Posts every existing business record that has no journal entry yet.
     *
     * Safe to run repeatedly: posting is idempotent on the source record, so a
     * second run posts nothing. A record that cannot be posted is counted and
     * reported rather than aborting the run — one bad row should not stop the
     * books from being built.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> backfill() {
        Map<String, Integer> posted = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();

        posted.put("sales", run(orderRepo.findByArchiveFalseOrderByIdAsc(), o -> {
            if (o.getOrderPlacedAt() == null) return null;
            return postSale(o);
        }, failures, "sale"));

        posted.put("expenses", run(expenseRepo.findAll(), e -> {
            if (e.getExpenseDate() == null) return null;
            return postExpense(e);
        }, failures, "expense"));

        posted.put("payroll", run(salaryRepo.findAll(), s -> {
            if (s.getPaymentMonth() == null) return null;
            return postPayroll(s);
        }, failures, "payroll"));

        posted.put("purchases", run(purchaseRepo.findAll(), p -> {
            if (p.getVendorInvoiceDate() == null) return null;
            return postPurchase(p);
        }, failures, "purchase"));

        posted.put("gstPayments", run(gstPaymentRepo.findAll(), g -> {
            if (g.getPaymentDate() == null) return null;
            return postGstPayment(g);
        }, failures, "gst payment"));

        int total = posted.values().stream().mapToInt(Integer::intValue).sum();
        log.info("Accounting backfill: {} entries posted, {} could not be posted", total, failures.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("postedByType", posted);
        out.put("totalPosted", total);
        out.put("failures", failures);
        return out;
    }

    @FunctionalInterface
    private interface Poster<T> { JournalEntryEntity post(T t) throws VeloriaException; }

    private <T> int run(List<T> records, Poster<T> poster, List<String> failures, String label) {
        int n = 0;
        for (T r : records) {
            try {
                if (poster.post(r) != null) n++;
            } catch (Exception e) {
                failures.add(label + ": " + e.getMessage());
            }
        }
        return n;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Maps an expense type onto its account, falling back to other expenses. */
    private String expenseAccount(String expenseType) {
        String t = expenseType == null ? "" : expenseType.toUpperCase();
        if (t.contains("MARKETING") || t.contains("AD_SPEND")) return MARKETING;
        if (t.contains("RENT")) return RENT;
        if (t.contains("UTILIT") || t.contains("ELECTRIC") || t.contains("INTERNET")) return UTILITIES;
        if (t.contains("SALARY") || t.contains("PAYROLL")) return PAYROLL;
        if (t.contains("COGS") || t.contains("PURCHASE")) return COGS;
        return OTHER_EXPENSE;
    }

    private String titleOf(ExpenseEntity e) {
        String type = nvl(e.getExpenseType(), "Expense").replace('_', ' ').toLowerCase();
        return Character.toUpperCase(type.charAt(0)) + type.substring(1)
                + (e.getSupplierName() != null ? " — " + e.getSupplierName() : "");
    }

    private LocalDate dateOf(java.time.Instant instant) {
        return instant == null ? LocalDate.now() : LocalDate.ofInstant(instant, IST);
    }

    private static long nz(Long v) { return v == null ? 0L : v; }

    private static String nvl(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
