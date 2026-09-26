package com.app.master.service.service.payment;

import com.app.master.service.core.entity.CustomerOrderEntity;
import com.app.master.service.core.entity.OrderReturnRequestEntity;
import com.app.master.service.core.entity.PaymentAttemptEntity;
import com.app.master.service.core.entity.PaymentRefundEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.order.OrderStatus;
import com.app.master.service.core.payment.PaymentAllocation;
import com.app.master.service.core.payment.PaymentStatus;
import com.app.master.service.core.payment.RefundStatus;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.CustomerOrderRepository;
import com.app.master.service.repository.admin.OrderReturnRequestRepository;
import com.app.master.service.repository.payment.PaymentAttemptRepository;
import com.app.master.service.repository.payment.PaymentRefundRepository;
import com.app.master.service.service.admin.AccountingPostingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Giving a customer their money back.
 *
 * <p>The approved workflow, and each step is a real gate rather than a stage
 * name:
 *
 * <pre>
 *   return requested → goods received → warehouse verified → refund confirmed
 *                                                          → refund issued
 * </pre>
 *
 * <p>A return request is not a refund. Anyone can ask; the money moves only once
 * the goods are physically back and someone has checked them, and then only
 * because an authorised person confirms it. That last step is explicit because
 * whether a return is <em>accepted</em> — as opposed to received and inspected —
 * is a commercial judgement the application has no rule for, and a judgement with
 * no rule belongs to a person, not to a default.
 *
 * <p>Four things the amount is not: it is not recomputed from today's prices, not
 * taken from the request, not more than the customer actually paid, and never
 * more than once. It comes from the allocation stored on the payment when it was
 * captured, so a price change or a tax-rule change afterwards cannot alter what
 * is given back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    /** Statuses meaning the goods are physically back with the seller. */
    private static final EnumSet<OrderStatus> GOODS_RECEIVED = EnumSet.of(
            OrderStatus.RECEIVED, OrderStatus.RETURNED, OrderStatus.PARTIALLY_RETURNED);

    /**
     * The persisted status names, for callers and queries.
     *
     * <p>{@link RefundStatus} is the authority on what they mean and which moves
     * between them are legal; these are here so a controller or a repository query
     * does not have to reach for {@code .name()}.
     */
    public static final String REQUESTED  = RefundStatus.REQUESTED.name();
    public static final String APPROVED   = RefundStatus.APPROVED.name();
    public static final String PROCESSING = RefundStatus.PROCESSING.name();
    public static final String REFUNDED   = RefundStatus.REFUNDED.name();
    public static final String FAILED     = RefundStatus.FAILED.name();

    /** Where a gateway refund goes: back to whatever instrument paid. */
    public static final String RAZORPAY_SOURCE = "RAZORPAY_SOURCE";

    private final PaymentRefundRepository refundRepo;
    /** Commits the refund request in its own transaction; see PaymentRefundWriter. */
    private final PaymentRefundWriter refundWriter;
    private final PaymentAttemptRepository attemptRepo;
    private final CustomerOrderRepository orderRepo;
    private final OrderReturnRequestRepository returnRepo;
    private final RazorpayGateway razorpay;
    private final AccountingPostingService accounting;

    // ── eligibility ──────────────────────────────────────────────────────────

    /**
     * Whether a return has earned a refund, and for how much.
     *
     * @param eligible    whether money may be returned at all
     * @param reason      why not, when it may not
     * @param amountPaise what would be refunded, from the stored allocation
     */
    public record Eligibility(boolean eligible, String reason, long amountPaise,
                              long productPaise, long gstPaise, Long attemptId, String destination) {

        static Eligibility no(String reason) {
            return new Eligibility(false, reason, 0, 0, 0, null, null);
        }
    }

    /**
     * Decides whether the approved preconditions for a refund are all met.
     *
     * <p>Read-only and side-effect free, so an admin screen can show the answer
     * without committing to it, and {@link #issue} can re-check it under a lock.
     */
    @Transactional(readOnly = true)
    public Eligibility eligibilityFor(Long returnRequestId) throws VeloriaException {
        OrderReturnRequestEntity ret = returnRepo.findById(returnRequestId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Return " + returnRequestId + " was not found"));

        // 1. Warehouse verification completed.
        if (!"VERIFIED".equals(ret.getStatus())) {
            return Eligibility.no("Return " + nvl(ret.getReturnNumber())
                    + " is " + ret.getStatus() + "; a refund needs a completed warehouse verification");
        }

        CustomerOrderEntity order = orderRepo.findByOrderCodeAndArchiveFalse(ret.getOrderCode())
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Order " + ret.getOrderCode() + " was not found"));

        // 2. The goods are actually back.
        OrderStatus status = OrderStatus.of(order.getStatus()).orElse(null);
        if (status == null || !GOODS_RECEIVED.contains(status)) {
            return Eligibility.no("Order " + order.getOrderCode() + " is " + order.getStatus()
                    + "; a refund needs the goods received back first");
        }

        // 3. Not already refunded. The unique index is the real guarantee; this
        //    is so the caller gets an explanation rather than a constraint error.
        Optional<PaymentRefundEntity> already = refundRepo.findByReturnRequestId(returnRequestId);
        if (already.isPresent() && !FAILED.equals(already.get().getStatus())) {
            return Eligibility.no("Return " + nvl(ret.getReturnNumber())
                    + " has already been refunded (" + already.get().getUuid() + ")");
        }

        // 4. Something was actually collected, on an attempt that can be refunded.
        return refundableAttempt(order);
    }

    /**
     * The captured payment a refund would be taken from, and how much of it.
     *
     * <p>The amount is the refundable part of the allocation recorded when the
     * payment was captured — product and its GST. Transportation and the COD
     * handling charge are not refunded, and their absence from
     * {@link PaymentAllocation#refundablePaise()} is where that policy lives.
     */
    private Eligibility refundableAttempt(CustomerOrderEntity order) {
        List<PaymentAttemptEntity> captured = attemptRepo
                .findByCustomerOrderIdOrderBySequenceNoAscIdAsc(order.getId()).stream()
                .filter(a -> PaymentStatus.of(a.getStatus()).map(PaymentStatus::isPaid).orElse(false))
                .toList();

        if (captured.isEmpty()) {
            return Eligibility.no("Nothing has been collected for order " + order.getOrderCode()
                    + ", so there is nothing to refund");
        }

        // Cash on delivery has no electronic destination and the schema holds no
        // customer bank or UPI details, so there is nowhere to send the money.
        // Refusing is the honest answer; inventing a destination is not.
        if (captured.stream().allMatch(a -> "COD".equals(a.getGateway()))) {
            return Eligibility.no("Order " + order.getOrderCode()
                    + " was paid in cash on delivery, which has no refund destination. "
                    + "The route for returning cash is an approved decision this application "
                    + "does not yet hold.");
        }

        long product = 0;
        long gst = 0;
        Long attemptId = null;
        for (PaymentAttemptEntity a : captured) {
            if ("COD".equals(a.getGateway())) continue;
            long already = nz(refundRepo.refundedTotalPaise(a.getId()));
            PaymentAllocation allocation = PaymentAllocation.of(a);
            long refundable = allocation.refundablePaise() - already;
            if (refundable <= 0) continue;

            // The first attempt with something left. Refunding one payment at a
            // time keeps each refund traceable to the payment it reverses, which
            // is what the gateway requires in any case.
            product = allocation.productPaise();
            gst = allocation.gstPaise();
            // A partially refunded attempt gives back only what remains, taken
            // from the product first so the tax already returned is not returned
            // twice.
            long overshoot = (product + gst) - refundable;
            if (overshoot > 0) {
                long fromProduct = Math.min(product, overshoot);
                product -= fromProduct;
                gst -= (overshoot - fromProduct);
            }
            attemptId = a.getId();
            break;
        }

        if (attemptId == null) {
            return Eligibility.no("Everything refundable on order " + order.getOrderCode()
                    + " has already been refunded");
        }
        return new Eligibility(true, null, product + gst, product, gst, attemptId, RAZORPAY_SOURCE);
    }

    // ── issuing ──────────────────────────────────────────────────────────────

    /**
     * Issues the refund a verified return has earned.
     *
     * <p>The order of operations is the point:
     *
     * <pre>
     *   REQUESTED   written and committed before anything is sent
     *   PROCESSING  committed before the gateway is called
     *   REFUNDED    only on the gateway's confirmation — and only then is
     *               anything posted to the ledger
     * </pre>
     *
     * <p>{@code PROCESSING} is committed <em>before</em> the call rather than after,
     * so that a refund which succeeds at Razorpay but fails on the way back is
     * already recorded as money in flight. Recording it afterwards would leave a
     * window in which the customer has been paid and the application still thinks
     * nothing has been sent — the one failure there is no recovering from.
     *
     * <p>A gateway failure marks the refund {@code FAILED} and posts nothing: the
     * books must say that no money moved, because none did.
     *
     * @param performedBy who confirmed the refund — it becomes part of the record
     */
    @PreAuthorize("hasAuthority('ADMIN_GST')")
    public PaymentRefundEntity issue(Long returnRequestId, String idempotencyKey, String performedBy)
            throws VeloriaException {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "A refund idempotency key is required");
        }

        // A repeat of the same request returns what the first one produced,
        // whatever state it reached.
        Optional<PaymentRefundEntity> replay = refundRepo.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            log.info("Refund request replayed for {}", replay.get().getUuid());
            return replay.get();
        }

        Eligibility eligibility = eligibilityFor(returnRequestId);
        if (!eligibility.eligible()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, eligibility.reason());
        }

        PaymentRefundEntity refund;
        try {
            refund = create(returnRequestId, idempotencyKey, eligibility, performedBy);
        } catch (DataIntegrityViolationException e) {
            // Either the key or the one-refund-per-return index caught a race.
            // Both mean: the money is already on its way, do not send it again.
            return resolveRace(returnRequestId, idempotencyKey, e);
        }

        return complete(refund, eligibility);
    }

    /**
     * Builds the refund request and hands it to {@link PaymentRefundWriter}, which
     * commits it in its own transaction before the gateway is called.
     */
    private PaymentRefundEntity create(Long returnRequestId, String idempotencyKey,
                                       Eligibility eligibility, String performedBy) {
        PaymentAttemptEntity attempt = attemptRepo.findById(eligibility.attemptId()).orElseThrow();
        return refundWriter.insert(PaymentRefundEntity.builder()
                .paymentAttemptId(attempt.getId())
                .customerOrderId(attempt.getCustomerOrderId())
                .orderCode(attempt.getOrderCode())
                .idempotencyKey(idempotencyKey)
                .returnRequestId(returnRequestId)
                .refundDestination(eligibility.destination())
                .amountPaise(eligibility.amountPaise())
                .productRefundedPaise(eligibility.productPaise())
                .gstRefundedPaise(eligibility.gstPaise())
                .status(REQUESTED)
                .reason(safe(performedBy))
                .createdAt(Instant.now())
                .build());
    }

    /**
     * Calls the gateway and records what it said.
     *
     * <p>Not transactional around the gateway call on purpose: an HTTP call inside
     * a database transaction holds a connection for as long as the gateway takes,
     * and a rollback cannot un-send money in any case.
     */
    private PaymentRefundEntity complete(PaymentRefundEntity refund, Eligibility eligibility) {
        PaymentAttemptEntity attempt = attemptRepo.findById(refund.getPaymentAttemptId()).orElseThrow();

        // In flight before it is sent. From here the money must be treated as gone,
        // whatever the gateway goes on to say.
        move(refund, RefundStatus.PROCESSING);
        refundRepo.save(refund);

        try {
            String gatewayRefundId = razorpay.createRefund(
                    attempt.getRazorpayPaymentId(), refund.getAmountPaise(),
                    refund.getUuid().toString());
            refund.setRazorpayRefundId(gatewayRefundId);
            move(refund, RefundStatus.REFUNDED);
            refund.setCompletedAt(Instant.now());
            refundRepo.save(refund);
            log.info("Refund {} completed for order {} ({} paise: {} product, {} GST) via {}",
                    refund.getUuid(), refund.getOrderCode(), refund.getAmountPaise(),
                    refund.getProductRefundedPaise(), refund.getGstRefundedPaise(), gatewayRefundId);
            postRefundQuietly(refund);
        } catch (VeloriaException e) {
            move(refund, RefundStatus.FAILED);
            refund.setFailureCode("GATEWAY_REFUND_FAILED");
            refund.setFailureDescription(trim(e.getMessage(), 512));
            refundRepo.save(refund);
            log.error("Refund {} failed at the gateway for order {}: {}",
                    refund.getUuid(), refund.getOrderCode(), e.getMessage());
        }
        return refund;
    }

    /**
     * Moves a refund to a new state, refusing a move the machine does not allow.
     *
     * <p>An illegal transition is a programming error, not a business outcome, so it
     * throws rather than being recorded — a refund that went from FAILED back to
     * REFUNDED would be a ledger entry with no event behind it.
     */
    private void move(PaymentRefundEntity refund, RefundStatus to) {
        RefundStatus from = RefundStatus.of(refund.getStatus()).orElseThrow(
                () -> new IllegalStateException("Refund " + refund.getUuid()
                        + " holds an unrecognised status: " + refund.getStatus()));
        if (from == to) return;
        if (!from.canMoveTo(to)) {
            throw new IllegalStateException("A refund that is " + from + " cannot become " + to
                    + ". Allowed: " + RefundStatus.namesOf(from.allowedNext()) + ".");
        }
        refund.setStatus(to.name());
    }

    /**
     * Posts the refund's accounting.
     *
     * <p>Failures are logged, never rethrown. The money has already left; an
     * accounting problem must not make the application forget a refund it
     * actually made. The posting is idempotent on the refund, so the backfill can
     * complete it later.
     */
    private void postRefundQuietly(PaymentRefundEntity refund) {
        try {
            if (accounting.postRefund(refund) != null) {
                log.info("Refund {} posted to the ledger", refund.getUuid());
            }
        } catch (Exception e) {
            log.error("Refund {} could not be posted: {}", refund.getUuid(), e.getMessage());
        }
    }

    private PaymentRefundEntity resolveRace(Long returnRequestId, String idempotencyKey,
                                            DataIntegrityViolationException e) throws VeloriaException {
        Optional<PaymentRefundEntity> winner = refundRepo.findByIdempotencyKey(idempotencyKey)
                .or(() -> refundRepo.findByReturnRequestId(returnRequestId));
        if (winner.isPresent()) {
            log.info("Concurrent refund request for return {} resolved to {}",
                    returnRequestId, winner.get().getUuid());
            return winner.get();
        }
        throw new VeloriaException(ResponseCode.CONFLICT,
                "A refund for this return is already being processed.");
    }

    // ── reading ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PaymentRefundEntity> refundsFor(String orderCode) throws VeloriaException {
        CustomerOrderEntity order = orderRepo.findByOrderCodeAndArchiveFalse(orderCode)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Order not found"));
        return new ArrayList<>(refundRepo.findByCustomerOrderIdOrderByIdAsc(order.getId()));
    }

    private static long nz(Long v) { return v == null ? 0L : v; }

    private static String nvl(String v) { return v == null ? "" : v; }

    private static String safe(String v) { return trim(v, 64); }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
