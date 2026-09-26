package com.app.master.service.repository.payment;

import com.app.master.service.core.entity.PaymentAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttemptEntity, Long> {

    Optional<PaymentAttemptEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentAttemptEntity> findByRazorpayOrderId(String razorpayOrderId);

    Optional<PaymentAttemptEntity> findByRazorpayPaymentId(String razorpayPaymentId);

    Optional<PaymentAttemptEntity> findByUuid(java.util.UUID uuid);

    List<PaymentAttemptEntity> findByCustomerOrderIdOrderBySequenceNoAscIdAsc(Long customerOrderId);

    /** Captured payments, for the accounting backfill to settle any it missed. */
    List<PaymentAttemptEntity> findByStatusOrderByIdAsc(String status);

    /**
     * Takes the row lock on an attempt before its status is changed.
     *
     * <p>The browser callback and the gateway's webhook can arrive at the same
     * instant for the same payment. Both would read the same status, both would
     * judge their transition legal against it, and both would write. Locking the
     * row makes the second one wait and re-read, so it sees what actually
     * committed and applies nothing on top of it.
     */
    @Query(nativeQuery = true, value = """
            SELECT status FROM payment_attempt WHERE id = :attemptId FOR UPDATE
            """)
    Optional<String> lockForStatusChange(@Param("attemptId") Long attemptId);

    /** What the customer has actually paid for this order, in paise. */
    @Query(nativeQuery = true, value = """
            SELECT COALESCE(SUM(amount_paise), 0) FROM payment_attempt
             WHERE customer_order_id = :orderId
               AND status IN ('CAPTURED', 'PARTIALLY_REFUNDED')
            """)
    Long capturedTotalPaise(@Param("orderId") Long orderId);

    /** What each head has received so far, so the next payment continues the waterfall. */
    @Query(nativeQuery = true, value = """
            SELECT COALESCE(SUM(gst_allocated_paise), 0)       AS gst,
                   COALESCE(SUM(transport_allocated_paise), 0) AS transport,
                   COALESCE(SUM(other_allocated_paise), 0)     AS other,
                   COALESCE(SUM(product_allocated_paise), 0)   AS product
              FROM payment_attempt
             WHERE customer_order_id = :orderId
               AND status IN ('CAPTURED', 'PARTIALLY_REFUNDED')
            """)
    List<Object[]> allocatedSoFar(@Param("orderId") Long orderId);

    /** How many attempts failed, for the failure-rate reporting operations asked for. */
    long countByCustomerOrderIdAndStatus(Long customerOrderId, String status);

    long countByStatus(String status);
}
