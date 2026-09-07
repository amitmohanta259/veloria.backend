package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An exchange or replacement (spec phases 14 and 15).
 *
 * Modelled as two accounting events, not an edit of the original order: the
 * returned item is credited through the existing return and credit-note
 * machinery, and the replacement is supplied on its own invoice. The original
 * invoice is never touched.
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "order_exchange_request")
public class OrderExchangeRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String exchangeNumber;

    /** EXCHANGE (different product) or REPLACEMENT (same product). */
    @Builder.Default private String exchangeType = "EXCHANGE";

    private Long originalOrderId;
    private String originalOrderCode;
    private Long originalInvoiceId;
    private String customerId;
    private String customerName;

    // Returned side — handled by the existing return / credit-note flow.
    private Long returnRequestId;
    private Long creditNoteId;
    @Builder.Default private Long returnedTaxablePaise = 0L;
    @Builder.Default private Long returnedTaxPaise = 0L;

    // Replacement side — its own supply.
    private Long replacementOrderId;
    private Long replacementInvoiceId;
    @Builder.Default private Long replacementTaxablePaise = 0L;
    @Builder.Default private Long replacementTaxPaise = 0L;

    // Difference between the two.
    @Builder.Default private Long taxableDifferencePaise = 0L;
    @Builder.Default private Long taxDifferencePaise = 0L;
    @Builder.Default private Long paymentDifferencePaise = 0L;

    /** COLLECT_FROM_CUSTOMER, REFUND_TO_CUSTOMER or NONE. */
    private String settlementDirection;

    /** INITIATED, RETURNED, REPLACED, COMPLETED, CANCELLED */
    @Builder.Default private String status = "INITIATED";

    @Column(columnDefinition = "TEXT")
    private String reason;

    private String taxPeriod;
    private Long organizationId;
    private Long gstRegistrationId;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    private String createdBy;
    private Instant completedAt;
}
