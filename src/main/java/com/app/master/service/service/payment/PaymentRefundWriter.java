package com.app.master.service.service.payment;

import com.app.master.service.core.entity.PaymentRefundEntity;
import com.app.master.service.repository.payment.PaymentRefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a refund request in a transaction of its own, before the gateway is
 * called.
 *
 * <p>A separate bean rather than a method on {@link RefundService}, because a call
 * to {@code this.method()} does not pass through the Spring proxy and the {@code
 * REQUIRES_NEW} would silently have no effect. Two things depend on it actually
 * taking effect:
 *
 * <ol>
 *   <li><b>The row is committed before the money moves.</b> A refund that succeeds
 *       at the gateway but is lost on the way back is the one failure there is no
 *       recovering from — the customer has their money and the application does
 *       not know.</li>
 *   <li><b>Losing the race to a unique index does not poison the caller's
 *       session.</b> The caller has to read back who won, which is the whole point
 *       of catching the violation.</li>
 * </ol>
 *
 * <p>The same arrangement as {@link PaymentAttemptWriter}, for the same reasons.
 */
@Service
@RequiredArgsConstructor
public class PaymentRefundWriter {

    private final PaymentRefundRepository refundRepo;

    /**
     * Writes the refund request, letting a unique-index violation surface.
     *
     * <p>{@code saveAndFlush} so {@code ux_payment_refund_per_return} is tested
     * here rather than at some later commit, where it would be too late to
     * recover — and, worse, after the gateway had already been called.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public PaymentRefundEntity insert(PaymentRefundEntity refund) {
        return refundRepo.saveAndFlush(refund);
    }
}
