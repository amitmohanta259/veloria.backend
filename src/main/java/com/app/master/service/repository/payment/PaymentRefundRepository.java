package com.app.master.service.repository.payment;

import com.app.master.service.core.entity.PaymentRefundEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRefundRepository extends JpaRepository<PaymentRefundEntity, Long> {

    Optional<PaymentRefundEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentRefundEntity> findByRazorpayRefundId(String razorpayRefundId);

    List<PaymentRefundEntity> findByPaymentAttemptIdOrderByIdAsc(Long paymentAttemptId);

    List<PaymentRefundEntity> findByCustomerOrderIdOrderByIdAsc(Long customerOrderId);

    /** For the accounting backfill: refunds the gateway finished, oldest first. */
    List<PaymentRefundEntity> findByStatusOrderByIdAsc(String status);

    /**
     * The refund raised for a verified return, whatever its state.
     *
     * <p>One return earns one refund. This is what an eligibility check reads
     * before creating another, and {@code ux_payment_refund_per_return} is what
     * enforces it when two checks run at once.
     */
    Optional<PaymentRefundEntity> findByReturnRequestId(Long returnRequestId);

    /** What has been given back across every attempt on one order. */
    @Query(nativeQuery = true, value = """
            SELECT COALESCE(SUM(amount_paise), 0) FROM payment_refund
             WHERE customer_order_id = :orderId
               AND status <> 'FAILED'
            """)
    Long refundedTotalForOrderPaise(@Param("orderId") Long orderId);

    /**
     * What has already been given back for this attempt.
     *
     * <p>Counts requests that have not failed, not only completed ones: a refund
     * in flight is money on its way out, and treating it as unspent would let a
     * second request refund the same amount again.
     */
    @Query(nativeQuery = true, value = """
            SELECT COALESCE(SUM(amount_paise), 0) FROM payment_refund
             WHERE payment_attempt_id = :attemptId
               AND status <> 'FAILED'
            """)
    Long refundedTotalPaise(@Param("attemptId") Long attemptId);
}
