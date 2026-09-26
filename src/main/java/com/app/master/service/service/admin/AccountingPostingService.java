package com.app.master.service.service.admin;

import com.app.master.service.core.entity.*;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.order.OrderStatus;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    /**
     * Income that is not a sale of goods. The COD handling charge is approved as
     * Other Income, and this account already existed for exactly that purpose —
     * no new code was created for it.
     */
    public static final String OTHER_INCOME      = "4090";
    public static final String COGS              = "5010";
    public static final String MARKETING         = "5100";
    public static final String PAYROLL           = "5200";
    public static final String RENT              = "5300";
    public static final String UTILITIES         = "5400";
    public static final String OTHER_EXPENSE     = "5900";

    /** The accounting source type for a customer order's sale. */
    public static final String SALE = "SALE";

    /**
     * The accounting source type for money actually received against an order.
     *
     * <p>Deliberately a separate source from {@link #SALE}: a sale and its
     * collection are different events, can happen months apart, and a partially
     * paid order has one sale and several collections. Keying the journal on the
     * payment attempt rather than the order is what lets each collection be
     * posted exactly once and lets two of them coexist.
     */
    public static final String PAYMENT_COLLECTION = "PAYMENT_COLLECTION";

    /**
     * The accounting source type for the cash-on-delivery handling charge.
     *
     * <p>Its own event, keyed on the order, because the charge is added when the
     * customer chooses COD — after the sale has been recognised. Posting it as
     * part of the sale would mean either rewriting a posted journal or delaying
     * the sale until the payment mode is known; neither is acceptable, and the
     * charge is a separate supply in any case.
     */
    public static final String COD_FEE = "COD_FEE";

    /** The accounting source type for money given back to a customer. */
    public static final String REFUND = "REFUND";

    private final JournalService journal;
    private final JournalEntryRepository journalRepo;
    private final CustomerOrderRepository orderRepo;
    private final com.app.master.service.repository.payment.PaymentAttemptRepository attemptRepo;
    private final com.app.master.service.repository.payment.PaymentRefundRepository refundRepo;
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
     *
     * <p><b>Two things this refuses to do</b>, both checked here rather than in
     * the caller so that changing or adding a caller cannot bypass them:
     *
     * <ol>
     *   <li><b>Book a cancelled order as revenue.</b> Eligibility is judged on
     *       the status read under the row lock, not on the copy the caller
     *       loaded, because a backfill reads every order before posting any.</li>
     *   <li><b>Post a sale that has already been accounted for</b> — including
     *       one that was subsequently reversed. {@code ux_journal_source} only
     *       constrains POSTED rows, so without this a reversal would be quietly
     *       undone by the next backfill run.</li>
     * </ol>
     *
     * <p>Runs in its own transaction so one order's posting is atomic on its own
     * terms: a failure rolls back that entry and its lines and leaves the rest of
     * a backfill alone, which is what the backfill has always claimed to do.
     *
     * @return the entry that was posted, or {@code null} when the order is not
     *         eligible or has already been accounted for
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public JournalEntryEntity postSale(CustomerOrderEntity order) throws VeloriaException {
        if (order == null || order.getId() == null || order.getOrderPlacedAt() == null) return null;

        // The lock serialises posting for this order and gives us the status as
        // it is now, not as it was when the caller read it.
        String currentStatus = orderRepo.lockForSalePosting(order.getId()).orElse(null);
        if (currentStatus == null) {
            log.debug("Order {} is gone or archived; nothing to post", order.getOrderCode());
            return null;
        }

        OrderStatus status = OrderStatus.of(currentStatus).orElse(null);
        if (status == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Order " + order.getOrderCode() + " holds a status accounting does not recognise: "
                    + currentStatus);
        }
        if (!status.isSaleEligible()) {
            log.info("Order {} is {} and is not a sale; no journal posted",
                    order.getOrderCode(), status);
            return null;
        }

        if (journalRepo.existsBySourceTypeAndSourceId(SALE, order.getId())) {
            log.debug("Order {} has already been accounted for; not posting again", order.getOrderCode());
            return null;
        }

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

        // Deferrable: the approved closed-period policy is that a customer's order
        // stands and its accounting waits for the next open period. The order's
        // own date is kept as the transaction date either way.
        return journal.post(new JournalService.Draft(
                dateOf(order.getOrderPlacedAt()), order.getOrderCode(), SALE, order.getId(),
                "Sale to " + nvl(order.getCustomerName(), "customer"), p).deferrable());
    }

    /**
     * The cash-on-delivery handling charge.
     *
     * <pre>
     *   Dr Accounts Receivable        charge + its tax
     *      Cr Other Income                          the charge
     *      Cr Output CGST / SGST / IGST              as the snapshot states
     * </pre>
     *
     * <p>This is what closes the gap P0-10 found. The charge is added to the order
     * when the customer chooses COD, which is after the sale has been recognised,
     * so until now the customer was asked for money that no receivable stood
     * behind — and the collection had to be capped to keep the receivable from
     * going negative. Raising the receivable here means the collection settles the
     * invoice in full, and the cap becomes a guard rather than the mechanism.
     *
     * <p><b>Refuses to post a charge whose tax has not been resolved.</b> The
     * charge is approved as taxable, so recognising it without its tax would
     * understate output tax by an unknown amount. The rate comes from the tax
     * master; where the master cannot price it, nothing is posted and the reason
     * is recorded on the order for anyone reconciling it — see
     * {@code CodFeeTaxResolver} and the COD-fee tax blocker in the P0-11 report.
     *
     * @return the entry posted, or {@code null} when there is no charge, its tax
     *         is unresolved, the order is not a sale, or it is already posted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public JournalEntryEntity postCodFee(CustomerOrderEntity order) throws VeloriaException {
        if (order == null || order.getId() == null) return null;

        long fee = nz(order.getCodFeePaise());
        if (fee <= 0) return null;

        if (!"RULE_APPLIED".equals(order.getCodFeeTaxResolution())) {
            log.warn("COD charge of {} paise on order {} is not being recognised: its tax is {}",
                    fee, order.getOrderCode(),
                    order.getCodFeeTaxResolution() == null ? "unresolved" : order.getCodFeeTaxResolution());
            return null;
        }

        // Same eligibility and same lock as a sale: a cancelled order has no
        // handling charge to recognise either.
        String currentStatus = orderRepo.lockForSalePosting(order.getId()).orElse(null);
        if (currentStatus == null) return null;
        OrderStatus status = OrderStatus.of(currentStatus).orElse(null);
        if (status == null || !status.isSaleEligible()) {
            log.info("Order {} is {} and its COD charge is not income; no journal posted",
                    order.getOrderCode(), currentStatus);
            return null;
        }

        if (journalRepo.existsBySourceTypeAndSourceId(COD_FEE, order.getId())) {
            log.debug("Order {} already has its COD charge accounted for", order.getOrderCode());
            return null;
        }

        long cgst = nz(order.getCodFeeCgstPaise());
        long sgst = nz(order.getCodFeeSgstPaise());
        long igst = nz(order.getCodFeeIgstPaise());
        long taxable = nz(order.getCodFeeTaxablePaise()) > 0 ? nz(order.getCodFeeTaxablePaise()) : fee;
        long total = taxable + cgst + sgst + igst;

        List<JournalService.Posting> p = new ArrayList<>();
        p.add(debit(RECEIVABLE, total, "COD fee " + order.getOrderCode()));
        p.add(credit(OTHER_INCOME, taxable, "COD Fee"));
        if (cgst > 0) p.add(credit(OUTPUT_CGST, cgst, "Output CGST on COD fee"));
        if (sgst > 0) p.add(credit(OUTPUT_SGST, sgst, "Output SGST on COD fee"));
        if (igst > 0) p.add(credit(OUTPUT_IGST, igst, "Output IGST on COD fee"));

        return journal.post(new JournalService.Draft(
                dateOf(order.getOrderPlacedAt()), order.getOrderCode(), COD_FEE, order.getId(),
                "COD fee on " + order.getOrderCode(), p).deferrable());
    }

    /**
     * Records money received against an order.
     *
     * <pre>
     *   Dr Bank (online) or Cash (cash on delivery)   amount collected
     *      Cr Accounts Receivable                                    the same
     * </pre>
     *
     * <p>This settles the receivable the sale created; it does not touch revenue
     * or tax, because none of those change when a customer pays. A partially
     * paid order therefore reduces its receivable twice, once per collection,
     * and is settled only when the second one lands.
     *
     * <p><b>Refuses to settle a receivable that does not exist.</b> Under the
     * approved model the sale is posted when the order is created, so by the
     * time money arrives there is normally something to clear. If there is not —
     * the sale could not be posted because its period was closed, say — this
     * posts nothing rather than crediting a receivable that was never debited
     * and driving the balance negative.
     *
     * <p>Idempotent on the payment attempt: {@code ux_journal_source} allows one
     * POSTED entry per source, and the any-status check below stops a reversed
     * collection being silently re-created, exactly as P0-5B does for sales.
     *
     * @return the entry posted, or {@code null} when there was nothing to post
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public JournalEntryEntity postPaymentCollection(PaymentAttemptEntity attempt) throws VeloriaException {
        if (attempt == null || attempt.getId() == null) return null;

        long amount = attempt.getAmountPaise() == null ? 0L : attempt.getAmountPaise();
        if (amount <= 0) return null;

        if (journalRepo.existsBySourceTypeAndSourceId(PAYMENT_COLLECTION, attempt.getId())) {
            log.debug("Payment {} has already been recorded as collected", attempt.getUuid());
            return null;
        }

        // Settling a receivable that was never raised would misstate the ledger
        // in both directions. Better to leave the collection unposted and
        // visible than to invent the other side of the entry.
        if (!journalRepo.existsBySourceTypeAndSourceId(SALE, attempt.getCustomerOrderId())) {
            log.warn("Payment {} collected for order {} but no sale has been posted; "
                   + "collection not recorded", attempt.getUuid(), attempt.getOrderCode());
            return null;
        }

        // Settle no more than was actually receivable.
        //
        // This is a guard, not the mechanism. P0-10 relied on it because the COD
        // handling charge raised no receivable, so a COD collection genuinely
        // exceeded the books; postCodFee now raises that receivable and a COD
        // collection settles the invoice exactly. What remains is a floor under
        // the receivable: crediting more than was ever debited would drive it
        // negative, and a negative receivable is not a number anyone can act on.
        //
        // If this ever does trigger, the excess is real money that the ledger has
        // no home for — it is logged rather than absorbed, because a silent
        // plug is how books stop meaning anything.
        long outstanding = outstandingReceivable(attempt.getCustomerOrderId());
        if (outstanding <= 0) {
            log.warn("Payment {} on order {} has nothing left to settle; collection not recorded",
                    attempt.getUuid(), attempt.getOrderCode());
            return null;
        }
        long settled = Math.min(amount, outstanding);
        if (settled < amount) {
            log.warn("Payment {} on order {} collected {} paise but only {} is receivable; "
                   + "the {} paise difference is unaccounted pending an approved treatment",
                    attempt.getUuid(), attempt.getOrderCode(), amount, settled, amount - settled);
        }

        // Cash on delivery is physical cash in someone's hand; a gateway payment
        // reaches the bank. Both accounts already exist and mean exactly this.
        String debitAccount = "COD".equals(attempt.getGateway()) ? CASH : BANK;
        String what = "COD".equals(attempt.getGateway()) ? "Cash collected on delivery" : "Payment received";

        LocalDate when = dateOf(attempt.getCapturedAt() != null
                ? attempt.getCapturedAt() : attempt.getCreatedAt());

        return journal.post(new JournalService.Draft(
                when, attempt.getOrderCode(), PAYMENT_COLLECTION, attempt.getId(),
                what + " for " + attempt.getOrderCode(),
                List.of(debit(debitAccount, settled, what),
                        credit(RECEIVABLE, settled, "Settles receivable on " + attempt.getOrderCode())))
                .deferrable());
    }

    /**
     * Money given back to a customer.
     *
     * <pre>
     *   Dr Sales                         the product value refunded
     *   Dr Output CGST / SGST / IGST      the tax refunded
     *      Cr Bank                                       what actually left
     * </pre>
     *
     * <p>An adjustment, not a deletion. The sale and the collection both happened
     * and both stay exactly as posted; this records the separate, later event of
     * giving part of the money back. Revenue and output tax come down by what was
     * returned, and the bank by what was actually paid out.
     *
     * <p>The figures come from the refund row, which took them from the payment's
     * stored allocation — not from today's product prices or tax rules. The
     * receivable is untouched: the collection already cleared it, and a refund
     * does not re-create it.
     *
     * <p><b>Only a refund the gateway has actually completed is posted.</b> A
     * requested or failed refund has not moved money, and the books must say what
     * happened rather than what was intended.
     *
     * @return the entry posted, or {@code null} when the refund is not complete,
     *         has nothing to post, or is already posted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public JournalEntryEntity postRefund(PaymentRefundEntity refund) throws VeloriaException {
        if (refund == null || refund.getId() == null) return null;

        if (!com.app.master.service.core.payment.RefundStatus.REFUNDED.name()
                .equals(refund.getStatus())) {
            log.debug("Refund {} is {}; nothing is posted until the gateway completes it",
                    refund.getUuid(), refund.getStatus());
            return null;
        }

        long product = nz(refund.getProductRefundedPaise());
        long gst = nz(refund.getGstRefundedPaise());
        long total = nz(refund.getAmountPaise());
        if (total <= 0) return null;

        if (product + gst != total) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Refund " + refund.getUuid() + " does not add up: product " + product
                    + " plus GST " + gst + " paise is not the " + total + " paise refunded. "
                    + "An entry that does not reconcile to the money is refused.");
        }

        if (journalRepo.existsBySourceTypeAndSourceId(REFUND, refund.getId())) {
            log.debug("Refund {} has already been accounted for", refund.getUuid());
            return null;
        }

        // Which tax heads to take back is not a decision made here: it is whichever
        // heads the original sale credited, read from the order's own snapshot.
        CustomerOrderEntity order = orderRepo.findById(refund.getCustomerOrderId()).orElse(null);
        if (order == null) return null;

        List<JournalService.Posting> p = new ArrayList<>();
        if (product > 0) p.add(debit(SALES, product, "Refund of goods returned"));
        if (gst > 0) p.addAll(taxRefundPostings(order, gst, refund));
        p.add(credit(BANK, total, "Refunded to the customer"));

        return journal.post(new JournalService.Draft(
                dateOf(refund.getCompletedAt() != null ? refund.getCompletedAt() : refund.getCreatedAt()),
                refund.getOrderCode(), REFUND, refund.getId(),
                "Refund on " + refund.getOrderCode(), p).deferrable());
    }

    /**
     * Splits a refunded tax amount across the heads the sale charged it under.
     *
     * <p>An intra-state supply charged CGST and SGST, an inter-state one IGST, and
     * the refund reverses whichever it was. The split is taken in the same
     * proportion as the original, and the last head absorbs the odd paise so the
     * postings total exactly what is being refunded.
     */
    private List<JournalService.Posting> taxRefundPostings(CustomerOrderEntity order, long gst,
                                                           PaymentRefundEntity refund)
            throws VeloriaException {
        long cgst = nz(order.getCgstAmount());
        long sgst = nz(order.getSgstAmount());
        long igst = nz(order.getIgstAmount());
        long charged = cgst + sgst + igst;

        if (charged == 0) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Refund " + refund.getUuid() + " gives back " + gst + " paise of tax, but order "
                    + refund.getOrderCode() + " has no tax recorded against it. Refused rather than "
                    + "credited to a head that was never charged.");
        }

        List<JournalService.Posting> p = new ArrayList<>();
        long placed = 0;
        if (igst > 0) {
            // Inter-state: one head, so it takes the whole amount.
            p.add(debit(OUTPUT_IGST, gst, "Refund of output IGST"));
            return p;
        }
        if (cgst > 0) {
            long share = gst * cgst / charged;
            p.add(debit(OUTPUT_CGST, share, "Refund of output CGST"));
            placed += share;
        }
        if (sgst > 0) {
            // The remainder, so rounding never loses or invents a paisa.
            p.add(debit(OUTPUT_SGST, gst - placed, "Refund of output SGST"));
        } else if (placed < gst) {
            p.add(debit(OUTPUT_CGST, gst - placed, "Refund of output CGST — balance"));
        }
        return p;
    }


    /** What this order still owes: its sale, less anything already collected or reversed. */
    private long outstandingReceivable(Long orderId) {
        Long net = journalRepo.outstandingReceivableFor(orderId);
        return net == null ? 0L : net;
    }

    /**
     * Takes a cancelled order's sale back out of the books.
     *
     * <p>Uses the existing reversal mechanism rather than deleting or editing
     * the original: the sale genuinely was posted, and a correction that erased
     * it would leave nothing to audit. The reversal is the mirror image, dated
     * today, so it lands in the current period rather than reopening a closed
     * one.
     *
     * <p><b>Idempotent.</b> Cancelling twice, or two operators cancelling at
     * once, produces one reversal: the order row is locked first, and an
     * already-reversed sale is left alone rather than reversed again.
     *
     * <p><b>This is not a refund.</b> It removes revenue and a receivable that
     * should never have stood. No money moves, because no money was ever
     * collected — payment collection does not exist in this application. A
     * refund, when payment exists, will be a separate event with its own
     * posting.
     *
     * @return the reversal entry, or {@code null} when there was nothing to reverse
     */
    @Transactional(rollbackFor = Exception.class)
    public JournalEntryEntity reverseSaleOfCancelledOrder(CustomerOrderEntity order, String reason)
            throws VeloriaException {
        if (order == null || order.getId() == null) return null;

        // Same lock the posting path takes, so a reversal and a concurrent
        // backfill posting cannot interleave on one order.
        String currentStatus = orderRepo.lockForSalePosting(order.getId()).orElse(null);
        if (currentStatus == null) return null;

        OrderStatus status = OrderStatus.of(currentStatus).orElse(null);
        if (status != OrderStatus.CANCELLED) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Only a cancelled order's sale may be reversed; " + order.getOrderCode()
                    + " is " + currentStatus);
        }

        var posted = journalRepo.findBySourceTypeAndSourceIdAndStatus(
                SALE, order.getId(), JournalService.POSTED);
        if (posted.isEmpty()) {
            // Case A: nothing was ever posted, or it has already been reversed.
            // Either way the books already say what they should.
            log.info("Order {} has no posted sale to reverse", order.getOrderCode());
            return null;
        }

        JournalEntryEntity reversal = journal.reverse(posted.get().getId(),
                reason == null || reason.isBlank() ? "Order cancelled" : reason);
        log.info("Order {} cancelled: sale {} reversed by {}",
                order.getOrderCode(), posted.get().getJournalNumber(), reversal.getJournalNumber());
        return reversal;
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
     * <p>Safe to run repeatedly: each posting rule refuses to write a record it
     * has already accounted for, so a second run posts nothing. A record that
     * cannot be posted is counted and reported rather than aborting the run —
     * one bad row should not stop the books from being built, and because each
     * sale is posted in its own transaction a failure on one order no longer
     * poisons the rest of the run.
     *
     * <p>Sales additionally refuse cancelled and failed orders; see
     * {@link #postSale}.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> backfill() {
        Map<String, Integer> posted = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();

        // postSale decides for itself whether an order is eligible and whether it
        // has already been accounted for, so this loop simply offers every order
        // and counts what was actually written. Orders are offered in ascending
        // id, so two concurrent runs take the per-order locks in the same order.
        List<CustomerOrderEntity> orders = orderRepo.findByArchiveFalseOrderByIdAsc();
        posted.put("sales", run(orders, this::postSale, failures, "sale"));

        // COD handling charges. After sales, because both debit the same
        // receivable and reading it back is simpler when the sale is already
        // there. Orders without a charge, or whose charge has no resolved tax,
        // are skipped by postCodFee itself.
        posted.put("codFees", run(orders, this::postCodFee, failures, "COD fee"));

        // Collections a capture could not post at the time — most often because
        // the sale had not been booked yet, or the period was closed. Runs after
        // sales so a collection posted here has a receivable to settle.
        posted.put("collections", run(
                attemptRepo.findByStatusOrderByIdAsc("CAPTURED"), this::postPaymentCollection,
                failures, "collection"));

        // Refunds the gateway completed but whose accounting did not land.
        posted.put("refunds", run(
                refundRepo.findByStatusOrderByIdAsc(
                        com.app.master.service.core.payment.RefundStatus.REFUNDED.name()),
                this::postRefund,
                failures, "refund"));

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
