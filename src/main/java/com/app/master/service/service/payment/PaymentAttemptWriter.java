package com.app.master.service.service.payment;

import com.app.master.service.core.entity.PaymentAttemptEntity;
import com.app.master.service.repository.payment.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts a payment attempt in a transaction of its own.
 *
 * <p>A separate bean rather than a method on {@link PaymentService}, because a
 * call to {@code this.method()} does not pass through the Spring proxy and the
 * {@code REQUIRES_NEW} would silently have no effect — the insert would join
 * the caller's transaction, and losing the race to a unique index would poison
 * it. The caller could then not read who won, which is the whole point of
 * catching the violation.
 *
 * <p>This is the same arrangement P0-3 uses for checkout idempotency, for the
 * same reason.
 */
@Service
@RequiredArgsConstructor
public class PaymentAttemptWriter {

    private final PaymentAttemptRepository attemptRepo;

    /**
     * Writes the attempt, letting a unique-index violation surface.
     *
     * <p>{@code saveAndFlush} so the constraint is tested here rather than at
     * some later commit, where it would be too late to recover cleanly.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public PaymentAttemptEntity insert(PaymentAttemptEntity attempt) {
        return attemptRepo.saveAndFlush(attempt);
    }
}
