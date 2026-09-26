package com.app.master.service.service.payment;

import com.app.master.service.core.entity.CustomerOrderEntity;
import com.app.master.service.core.entity.PaymentAttemptEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.order.OrderStatus;
import com.app.master.service.core.payment.*;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.CustomerOrderRepository;
import com.app.master.service.repository.payment.PaymentAttemptRepository;
import com.app.master.service.service.admin.impl.SalesOrderServiceImpl;
import com.app.master.service.service.client.ClientSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Collecting money for an order.
 *
 * <p>Three things decide whether this is correct, and each is enforced in one
 * place rather than relied on by convention:
 *
 * <ol>
 *   <li><b>The server owns the amount.</b> It is computed from the order's own
 *       stored figures by {@link OrderInvoice}. No request body can influence
 *       what the customer is charged.</li>
 *   <li><b>A signature is evidence, not proof of payment.</b> It establishes
 *       that a message came from Razorpay. What the payment actually is — its
 *       status, amount, currency and which order it belongs to — is read back
 *       from Razorpay before anything is marked paid.</li>
 *   <li><b>Everything is idempotent.</b> The browser callback and the webhook
 *       routinely arrive together, and Razorpay redelivers webhooks. Unique
 *       indexes and a row lock on the attempt make the second arrival a no-op
 *       rather than a second capture, a second cancellation, or a second
 *       release of stock.</li>
 * </ol>
 *
 * <p>Order status and payment status are kept apart throughout. An order being
 * packed while its payment is captured is ordinary, and neither field is ever
 * derived from the other.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    /** The approved flat handling charge for cash on delivery, in paise. */
    public static final long COD_FEE_PAISE = 5000L;

    private final RazorpayGateway razorpay;
    private final RazorpayProperties razorpayProperties;
    private final PaymentAttemptRepository attemptRepo;
    private final CustomerOrderRepository orderRepo;
    private final ClientSessionStore sessionStore;
    private final SalesOrderServiceImpl orderService;
    /** Writes a new attempt in its own transaction; see PaymentAttemptWriter. */
    private final PaymentAttemptWriter attemptWriter;
    /** Posts the collection that settles the receivable. */
    private final com.app.master.service.service.admin.AccountingPostingService accounting;
    /** Prices the tax on the COD handling charge from the tax master. */
    private final CodFeeTaxResolver codFeeTax;
    private final com.app.master.service.repository.payment.PaymentRefundRepository refundRepo;

    // ── initiation ───────────────────────────────────────────────────────────

    /** What the browser needs to open Checkout. The secret is not among it. */
    public record CheckoutSession(
            String attemptUuid,
            String razorpayKeyId,
            String razorpayOrderId,
            long amountPaise,
            String currency,
            String orderCode,
            boolean cashOnDelivery) {}

    /**
     * Prepares a payment for an order the caller owns.
     *
     * <p>Returns the same checkout session for a repeated request carrying the
     * same idempotency key, so a double-click or a refreshed page does not open
     * a second payable transaction.
     */
    public CheckoutSession initiate(String token, String orderCode, String modeRaw, String idempotencyKey)
            throws VeloriaException {

        String customerId = requireCustomer(token);
        PaymentMode mode = PaymentMode.of(modeRaw).orElseThrow(() -> new VeloriaException(
                ResponseCode.BAD_REQUEST, "Unknown payment mode: " + modeRaw));

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "A payment idempotency key is required");
        }

        // A repeat of the same request returns what the first one produced.
        Optional<PaymentAttemptEntity> existing = attemptRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), customerId);
        }

        CustomerOrderEntity order = ownedOrder(orderCode, customerId);
        assertOrderIsPayable(order);

        long payable = payableFor(order, mode);

        PaymentAttemptEntity attempt = PaymentAttemptEntity.builder()
                .customerOrderId(order.getId())
                .orderCode(order.getOrderCode())
                .customerId(customerId)
                .idempotencyKey(idempotencyKey)
                .sequenceNo(nextSequence(order.getId()))
                .gateway(mode.isOnline() ? "RAZORPAY" : "COD")
                .status(PaymentStatus.INITIATED.name())
                .amountPaise(payable)
                .currency("INR")
                .createdAt(Instant.now())
                .build();

        try {
            attempt = attemptWriter.insert(attempt);
        } catch (DataIntegrityViolationException e) {
            // Either the same key raced us, or this order already has a live
            // attempt. Both mean: do not open a second payable transaction.
            //
            // The insert had its own transaction, so only that rolled back and
            // the re-read below runs on a clean session. Doing this inside the
            // failed transaction would fail again — a constraint violation
            // poisons the persistence context.
            return resolveRace(idempotencyKey, orderCode, customerId, e);
        }

        if (!mode.isOnline()) {
            // Cash on delivery never touches Razorpay. The attempt stands as
            // the record of an amount due at the door — and it is genuinely
            // pending, waiting on the courier rather than on a gateway, which
            // is why it moves past INITIATED here. INITIATED means "no payment
            // has been set in motion yet", and for COD one has.
            attempt.setStatus(PaymentStatus.PENDING.name());
            attempt.setUpdatedAt(Instant.now());
            attempt = attemptRepo.save(attempt);
            recordChosenMode(order, mode);
            log.info("COD selected for order {} (attempt {}, {} paise due on delivery)",
                    orderCode, attempt.getUuid(), payable);
            return new CheckoutSession(attempt.getUuid().toString(), null, null,
                    payable, "INR", orderCode, true);
        }

        String razorpayOrderId = razorpay.createOrder(payable, "INR", attempt.getUuid().toString());
        attempt.setRazorpayOrderId(razorpayOrderId);
        attempt.setStatus(PaymentStatus.PENDING.name());
        attempt.setUpdatedAt(Instant.now());
        attemptRepo.save(attempt);
        recordChosenMode(order, mode);

        log.info("Payment initiated for order {}: attempt {}, razorpay order {}, {} paise",
                orderCode, attempt.getUuid(), razorpayOrderId, payable);

        return new CheckoutSession(attempt.getUuid().toString(), razorpayProperties.getKeyId(),
                razorpayOrderId, payable, "INR", orderCode, false);
    }

    /**
     * What this payment would collect, without creating anything.
     *
     * <p>The checkout screen needs the figure before the customer commits, and
     * it must be the same figure the payment will actually ask for — so it comes
     * from here rather than being recomputed in the browser.
     */
    @Transactional(readOnly = true)
    public long quote(String token, String orderCode, String modeRaw) throws VeloriaException {
        String customerId = requireCustomer(token);
        PaymentMode mode = PaymentMode.of(modeRaw).orElseThrow(() -> new VeloriaException(
                ResponseCode.BAD_REQUEST, "Unknown payment mode: " + modeRaw));
        return payableFor(ownedOrder(orderCode, customerId), mode);
    }

    /**
     * What this payment should collect, from the order's own figures.
     *
     * <p>{@link OrderInvoice} adds the transportation charge and any other
     * charge to the product and GST the order already carries. For a partial
     * payment the first collection is half the invoice and the second is
     * whatever is genuinely still owed, so the two can never overshoot.
     */
    private long payableFor(CustomerOrderEntity order, PaymentMode mode) throws VeloriaException {
        if (mode == PaymentMode.COD) {
            applyCodFee(order);
        }
        OrderInvoice invoice = OrderInvoice.of(order);
        long alreadyPaid = nz(attemptRepo.capturedTotalPaise(order.getId()));

        long payable = switch (mode) {
            case ONLINE_FULL, COD -> invoice.finalInvoiceTotalPaise() - alreadyPaid;
            case ONLINE_PARTIAL   -> alreadyPaid == 0
                    ? invoice.partialFirstPaise()
                    : invoice.finalInvoiceTotalPaise() - alreadyPaid;
        };

        if (payable <= 0) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "This order has already been paid in full.");
        }
        return payable;
    }

    /**
     * Puts the approved handling charge, and its tax, on a cash-on-delivery order.
     *
     * <p>Written once and then left alone: the charge and the rate that applied
     * are frozen on the order, so a return prepared next year reproduces what the
     * customer was actually charged rather than what today's rules would charge.
     *
     * <p>The tax comes from the tax master by service code and order date. Where
     * the master cannot price it the charge still stands — it is approved at ₹50 —
     * but the tax figures stay zero and the reason is recorded, so nothing is
     * invoiced or posted at a rate nobody approved. The accounting refuses to
     * recognise an unresolved charge for the same reason.
     */
    private void applyCodFee(CustomerOrderEntity order) {
        if (nz(order.getCodFeePaise()) != 0L) return;

        CodFeeTaxResolver.CodFeeTax tax = codFeeTax.resolve(order, COD_FEE_PAISE);

        order.setCodFeePaise(COD_FEE_PAISE);
        order.setCodFeeTaxablePaise(tax.taxablePaise());
        order.setCodFeeSacCode(tax.sacCode());
        order.setCodFeeTaxRateBp(tax.rateBp());
        order.setCodFeeCgstPaise(tax.cgstPaise());
        order.setCodFeeSgstPaise(tax.sgstPaise());
        order.setCodFeeIgstPaise(tax.igstPaise());
        order.setCodFeeTaxPaise(tax.totalTaxPaise());
        order.setCodFeeTaxResolution(tax.resolution());
        orderRepo.save(order);

        recordCodFeeIncome(order);
    }

    /**
     * Raises the receivable and the income the handling charge represents.
     *
     * <p>Posted here rather than as part of the sale because the charge only comes
     * into existence when the customer chooses COD, which is after the sale has
     * been recognised. That ordering is what left P0-10 with a customer paying ₹50
     * that no receivable stood behind.
     *
     * <p>Failures are logged, never rethrown: an accounting problem must not stop
     * a customer from choosing cash on delivery. The posting is idempotent on the
     * order, so the backfill can complete it later.
     */
    private void recordCodFeeIncome(CustomerOrderEntity order) {
        try {
            if (accounting.postCodFee(order) != null) {
                log.info("COD fee recognised for order {} ({} paise plus {} paise tax)",
                        order.getOrderCode(), order.getCodFeePaise(), order.getCodFeeTaxPaise());
            }
        } catch (Exception e) {
            log.error("COD fee could not be recognised for order {}: {}",
                    order.getOrderCode(), e.getMessage());
        }
    }

    // ── verification ─────────────────────────────────────────────────────────

    /**
     * Confirms a payment the browser has just reported.
     *
     * <p>The signature is checked first because an unsigned callback is not
     * worth acting on. Everything after it re-establishes the facts from
     * Razorpay rather than from the request: what the payment's status is, how
     * much it was for, in what currency, and which gateway order it belongs to.
     */
    @Transactional(rollbackFor = Exception.class)
    public PaymentAttemptEntity verify(String token, String razorpayOrderId, String razorpayPaymentId,
                                       String signature) throws VeloriaException {

        String customerId = requireCustomer(token);

        if (!razorpay.isCallbackSignatureValid(razorpayOrderId, razorpayPaymentId, signature)) {
            log.warn("Rejected payment callback for razorpay order {}: signature did not verify", razorpayOrderId);
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "This payment could not be verified.");
        }

        PaymentAttemptEntity attempt = attemptRepo.findByRazorpayOrderId(razorpayOrderId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Payment not found"));

        if (!attempt.getCustomerId().equals(customerId)) {
            log.warn("Payment {} verification refused: requested by a customer who does not own it",
                    attempt.getUuid());
            throw new VeloriaException(ResponseCode.NOT_FOUND, "Payment not found");
        }

        return applyGatewayTruth(attempt, razorpayPaymentId, "callback");
    }

    /**
     * Reads the payment back from Razorpay and moves the attempt to match it.
     *
     * <p>Shared by the browser callback and the webhook, because both are
     * reporting the same event and must converge on one answer. The attempt row
     * is locked first, so whichever arrives second sees the state the first one
     * committed and adds nothing to it.
     */
    @Transactional(rollbackFor = Exception.class)
    public PaymentAttemptEntity applyGatewayTruth(PaymentAttemptEntity attempt, String razorpayPaymentId,
                                                  String source) throws VeloriaException {

        String lockedStatus = attemptRepo.lockForStatusChange(attempt.getId())
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Payment not found"));
        PaymentStatus current = PaymentStatus.of(lockedStatus).orElseThrow(() -> new VeloriaException(
                ResponseCode.INTERNAL_ERROR, "Payment holds an unrecognised status"));

        // Already settled by whichever of the two got here first.
        if (current == PaymentStatus.CAPTURED || current.isTerminal()) {
            log.info("Payment {} is already {} ({} arrived after the fact); nothing to do",
                    attempt.getUuid(), current, source);
            return attemptRepo.findById(attempt.getId()).orElseThrow();
        }

        RazorpayGateway.PaymentView view = razorpay.fetchPayment(razorpayPaymentId);

        // The payment must belong to the gateway order this attempt created.
        if (view.orderId() != null && !view.orderId().equals(attempt.getRazorpayOrderId())) {
            log.warn("Payment {} claims gateway order {} but attempt {} expects {}",
                    razorpayPaymentId, view.orderId(), attempt.getUuid(), attempt.getRazorpayOrderId());
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "This payment does not belong to that order.");
        }
        if (!"INR".equalsIgnoreCase(view.currency())) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "Unexpected payment currency.");
        }
        if (view.amountPaise() != attempt.getAmountPaise()) {
            log.warn("Payment {} is for {} paise but attempt {} expects {}",
                    razorpayPaymentId, view.amountPaise(), attempt.getUuid(), attempt.getAmountPaise());
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "The amount paid does not match this order. Nothing has been changed.");
        }

        PaymentAttemptEntity fresh = attemptRepo.findById(attempt.getId()).orElseThrow();
        fresh.setRazorpayPaymentId(razorpayPaymentId);
        fresh.setPaymentMethod(view.method());
        if (view.feePaise() != null) recordGatewayFee(fresh, view.feePaise());
        fresh.setUpdatedAt(Instant.now());

        if (view.isCaptured()) {
            transition(fresh, current, PaymentStatus.CAPTURED);
            fresh.setCapturedAt(Instant.now());
            allocate(fresh);
            attemptRepo.save(fresh);
            log.info("Payment {} captured for order {} ({} paise, via {}, source {})",
                    fresh.getUuid(), fresh.getOrderCode(), fresh.getAmountPaise(), view.method(), source);
            recordCollection(fresh);
            return fresh;
        }

        if (view.isAuthorized()) {
            transition(fresh, current, PaymentStatus.AUTHORIZED);
            attemptRepo.save(fresh);
            log.info("Payment {} authorized but not yet captured (source {})", fresh.getUuid(), source);
            return fresh;
        }

        if (view.isFailed()) {
            return fail(fresh, current, view.errorCode(), view.errorDescription(), view.errorSource(), source);
        }

        // created / pending: leave it alone and let the webhook settle it.
        log.info("Payment {} is '{}' at the gateway; leaving the attempt as {} (source {})",
                fresh.getUuid(), view.status(), current, source);
        attemptRepo.save(fresh);
        return fresh;
    }

    /**
     * Records a failed payment and cancels the order it was for.
     *
     * <p>The approved behaviour: the order is cancelled, which releases its
     * stock through the existing derived-inventory model, and no sale and no
     * collection is recognised. Cancellation is idempotent in the order service,
     * so a duplicate callback or a redelivered webhook cannot release the same
     * units twice.
     */
    private PaymentAttemptEntity fail(PaymentAttemptEntity attempt, PaymentStatus current,
                                      String code, String description, String failureSource, String source)
            throws VeloriaException {

        transition(attempt, current, PaymentStatus.FAILED);
        attempt.setFailedAt(Instant.now());
        attempt.setFailureCode(safe(code, 64));
        attempt.setFailureDescription(safe(description, 512));
        attempt.setFailureSource(safe(failureSource, 64));
        attemptRepo.save(attempt);

        log.warn("Payment {} failed for order {} (code {}, source {})",
                attempt.getUuid(), attempt.getOrderCode(), attempt.getFailureCode(), source);

        orderService.cancelAs(attempt.getOrderCode(), CancellationActor.SYSTEM,
                CancellationReason.PAYMENT_FAILED, null, null);

        return attempt;
    }

    /**
     * Records the gateway's fee and the approved split of it.
     *
     * <p>The fee is whatever the gateway says it charged. The 50/50 division is
     * computed from that figure and never from a rate — see {@link
     * GatewayFeeSplit}. All five numbers are stored so the books, when they can
     * finally record this, can be reconciled against what the gateway did.
     *
     * <p><b>Nothing is posted.</b> The business half is an expense against an
     * account that has no dedicated code, the customer half has no approved
     * accounting or tax treatment, and a capture is not a settlement in any case.
     * Recording the facts is separate from recognising them, and only the first is
     * decided.
     */
    private void recordGatewayFee(PaymentAttemptEntity attempt, long feePaise) {
        try {
            GatewayFeeSplit split = GatewayFeeSplit.of(attempt.getAmountPaise(), feePaise);
            attempt.setGatewayFeePaise(split.feePaise());
            attempt.setGatewayFeeBusinessPaise(split.businessPaise());
            attempt.setGatewayFeeCustomerPaise(split.customerPaise());
            attempt.setNetSettlementPaise(split.netSettlementPaise());
        } catch (IllegalArgumentException e) {
            // A fee the gateway reports that does not fit the payment is a fact we
            // do not understand. Keep the fee, leave the split unstated rather
            // than storing a division of a figure that makes no sense.
            attempt.setGatewayFeePaise(feePaise);
            log.warn("Gateway fee on payment {} could not be split: {}", attempt.getUuid(), e.getMessage());
        }
    }

    /** Applies the waterfall against whatever each head still owes. */
    private void allocate(PaymentAttemptEntity attempt) {
        CustomerOrderEntity order = orderRepo.findById(attempt.getCustomerOrderId()).orElseThrow();
        OrderInvoice invoice = OrderInvoice.of(order);

        long[] taken = allocatedSoFar(order.getId());

        PaymentAllocation allocation = PaymentAllocation.waterfall(
                attempt.getAmountPaise(),
                invoice.gstPaise()       - taken[0],
                invoice.transportPaise() - taken[1],
                invoice.otherPaise()     - taken[2],
                invoice.productPaise()   - taken[3]);

        attempt.setGstAllocatedPaise(allocation.gstPaise());
        attempt.setTransportAllocatedPaise(allocation.transportPaise());
        attempt.setOtherAllocatedPaise(allocation.otherPaise());
        attempt.setProductAllocatedPaise(allocation.productPaise());
    }

    /** {gst, transport, other, product} already settled by captured payments. */
    private long[] allocatedSoFar(Long orderId) {
        List<Object[]> rows = attemptRepo.allocatedSoFar(orderId);
        if (rows.isEmpty() || rows.get(0) == null) return new long[]{0, 0, 0, 0};
        Object[] r = rows.get(0);
        return new long[]{num(r[0]), num(r[1]), num(r[2]), num(r[3])};
    }

    private void transition(PaymentAttemptEntity attempt, PaymentStatus from, PaymentStatus to)
            throws VeloriaException {
        if (from == to) return;
        if (!from.canMoveTo(to)) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A payment that is " + from + " cannot become " + to
                    + ". Allowed: " + PaymentStatus.namesOf(from.allowedNext()) + ".");
        }
        attempt.setStatus(to.name());
    }


    /**
     * Settles the receivable this payment cleared.
     *
     * <p>Failures are logged, never rethrown. Money has already left the
     * customer's account by this point, so an accounting problem — a closed
     * period, a sale that was never posted — must not roll the capture back and
     * leave a payment the application has no record of. The collection is
     * idempotent on the attempt, so it can safely be posted later by the
     * accounting backfill instead.
     */
    private void recordCollection(PaymentAttemptEntity attempt) {
        try {
            if (accounting.postPaymentCollection(attempt) != null) {
                log.info("Collection posted for payment {} on order {}",
                        attempt.getUuid(), attempt.getOrderCode());
            }
        } catch (Exception e) {
            log.error("Collection could not be posted for payment {} on order {}: {}",
                    attempt.getUuid(), attempt.getOrderCode(), e.getMessage());
        }
    }

    /**
     * Records the cash a courier collected at the door.
     *
     * <p>Cash on delivery has no gateway event, so the collection is driven by
     * the order reaching the customer. Idempotent: a second delivery update, or
     * two of them at once, settles the receivable once.
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordCodCollection(Long orderId, String orderCode) {
        for (PaymentAttemptEntity attempt
                : attemptRepo.findByCustomerOrderIdOrderBySequenceNoAscIdAsc(orderId)) {

            if (!"COD".equals(attempt.getGateway())) continue;

            PaymentStatus current = PaymentStatus.of(attempt.getStatus()).orElse(null);
            if (current == null || current.isPaid() || current.isTerminal()) continue;

            String locked = attemptRepo.lockForStatusChange(attempt.getId()).orElse(null);
            PaymentStatus afterLock = PaymentStatus.of(locked).orElse(null);
            if (afterLock == null || afterLock.isPaid() || afterLock.isTerminal()) continue;

            PaymentAttemptEntity fresh = attemptRepo.findById(attempt.getId()).orElseThrow();
            try {
                transition(fresh, afterLock, PaymentStatus.CAPTURED);
            } catch (VeloriaException e) {
                log.warn("COD collection skipped for {}: {}", orderCode, e.getMessage());
                continue;
            }
            fresh.setCapturedAt(Instant.now());
            fresh.setPaymentMethod("cod");
            fresh.setUpdatedAt(Instant.now());
            allocate(fresh);
            attemptRepo.save(fresh);

            log.info("COD cash collected for order {} ({} paise)", orderCode, fresh.getAmountPaise());
            recordCollection(fresh);
        }
    }

    // ── reading ──────────────────────────────────────────────────────────────

    /**
     * What has been paid for one of the caller's own orders, and what is left.
     *
     * <p>The outstanding figure is derived from the captured attempts rather
     * than stored on the order, so it cannot drift away from the payments that
     * actually happened.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> summaryFor(String token, String orderCode) throws VeloriaException {
        String customerId = requireCustomer(token);
        CustomerOrderEntity order = ownedOrder(orderCode, customerId);

        OrderInvoice invoice = OrderInvoice.of(order);
        long paid = nz(attemptRepo.capturedTotalPaise(order.getId()));

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("orderCode", orderCode);
        out.put("paymentMode", order.getPaymentMode());
        // Every charge as its own line. A customer who is charged a handling fee
        // is entitled to see it as a handling fee, and the books record it as
        // separate income, so the summary must not fold it into anything.
        out.put("productPaise", invoice.productPaise());
        out.put("gstPaise", invoice.gstPaise());
        out.put("transportPaise", invoice.transportPaise());
        out.put("otherChargesPaise", invoice.otherChargesPaise());
        out.put("codFeePaise", invoice.codFeePaise());
        out.put("codFeeTaxPaise", invoice.codFeeTaxPaise());
        out.put("codFeeTaxRateBp", order.getCodFeeTaxRateBp());
        out.put("finalInvoiceTotalPaise", invoice.finalInvoiceTotalPaise());
        out.put("amountPaidPaise", paid);
        out.put("amountRefundedPaise", nz(refundRepo.refundedTotalForOrderPaise(order.getId())));
        out.put("amountOutstandingPaise", Math.max(0, invoice.finalInvoiceTotalPaise() - paid));
        out.put("fullyPaid", paid >= invoice.finalInvoiceTotalPaise());

        out.put("attempts", attemptRepo.findByCustomerOrderIdOrderBySequenceNoAscIdAsc(order.getId())
                .stream().map(a -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("paymentId", a.getUuid());
                    m.put("sequence", a.getSequenceNo());
                    m.put("status", a.getStatus());
                    m.put("amountPaise", a.getAmountPaise());
                    m.put("method", a.getPaymentMethod());
                    // The allocation this payment carried, as it was recorded.
                    m.put("allocatedGstPaise", a.getGstAllocatedPaise());
                    m.put("allocatedTransportPaise", a.getTransportAllocatedPaise());
                    m.put("allocatedOtherPaise", a.getOtherAllocatedPaise());
                    m.put("allocatedProductPaise", a.getProductAllocatedPaise());
                    m.put("failureCode", a.getFailureCode());
                    return m;
                }).toList());
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String requireCustomer(String token) throws VeloriaException {
        ClientSessionStore.SessionData session = sessionStore.get(token);
        if (session == null) {
            throw new VeloriaException(ResponseCode.UNAUTHORIZED, "Session expired. Please sign in again.");
        }
        return session.userId();
    }

    /** The order, if it belongs to this customer. Otherwise indistinguishable from absent. */
    private CustomerOrderEntity ownedOrder(String orderCode, String customerId) throws VeloriaException {
        CustomerOrderEntity order = orderRepo.findByOrderCodeAndArchiveFalse(orderCode)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Order not found"));
        if (!java.util.Objects.equals(order.getCustomerId(), customerId)) {
            log.warn("Payment refused for order {}: requested by a customer who does not own it", orderCode);
            throw new VeloriaException(ResponseCode.NOT_FOUND, "Order not found");
        }
        return order;
    }

    private void assertOrderIsPayable(CustomerOrderEntity order) throws VeloriaException {
        OrderStatus status = com.app.master.service.core.order.OrderStatus.of(order.getStatus())
                .orElseThrow(() -> new VeloriaException(ResponseCode.INTERNAL_ERROR,
                        "Order holds an unrecognised status"));
        if (status == OrderStatus.CANCELLED || status == OrderStatus.PAYMENT_FAILED) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "This order is no longer active and cannot be paid for.");
        }
    }

    private void recordChosenMode(CustomerOrderEntity order, PaymentMode mode) {
        if (order.getPaymentMode() == null) {
            order.setPaymentMode(mode.name());
            orderRepo.save(order);
        }
    }

    private int nextSequence(Long orderId) {
        return attemptRepo.findByCustomerOrderIdOrderBySequenceNoAscIdAsc(orderId).stream()
                .filter(a -> PaymentStatus.of(a.getStatus()).map(PaymentStatus::isPaid).orElse(false))
                .mapToInt(a -> a.getSequenceNo() == null ? 1 : a.getSequenceNo())
                .max().orElse(0) + 1;
    }

    private CheckoutSession replay(PaymentAttemptEntity attempt, String customerId) throws VeloriaException {
        if (!attempt.getCustomerId().equals(customerId)) {
            throw new VeloriaException(ResponseCode.NOT_FOUND, "Payment not found");
        }
        boolean cod = "COD".equals(attempt.getGateway());
        log.info("Payment request replayed for attempt {}", attempt.getUuid());
        return new CheckoutSession(attempt.getUuid().toString(),
                cod ? null : razorpayProperties.getKeyId(), attempt.getRazorpayOrderId(),
                attempt.getAmountPaise(), attempt.getCurrency(), attempt.getOrderCode(), cod);
    }

    /**
     * Works out which unique index a concurrent request hit and answers
     * accordingly, rather than surfacing a database error to a customer.
     */
    private CheckoutSession resolveRace(String idempotencyKey, String orderCode, String customerId,
                                        DataIntegrityViolationException e) throws VeloriaException {
        Optional<PaymentAttemptEntity> winner = attemptRepo.findByIdempotencyKey(idempotencyKey);
        if (winner.isPresent()) {
            return replay(winner.get(), customerId);
        }
        log.info("Order {} already has a payment in progress", orderCode);
        throw new VeloriaException(ResponseCode.BAD_REQUEST,
                "A payment for this order is already in progress. Please finish or close it before starting another.");
    }

    private static long num(Object o) { return o == null ? 0L : ((Number) o).longValue(); }
    private static long nz(Long v) { return v == null ? 0L : v; }

    /** Gateway text is not ours; truncate it and never let it grow a column. */
    private static String safe(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
