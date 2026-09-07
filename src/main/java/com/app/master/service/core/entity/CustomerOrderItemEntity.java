package com.app.master.service.core.entity;

import com.app.master.service.core.dto.Base;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "customer_order_item")
public class CustomerOrderItemEntity extends Base {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    private Long customerOrderId;
    private UUID productUuid;
    private String currency;
    private String selectedDimension;
    private String comment;
    private Integer rating;
    private String size;
    private String reasonForReturn;
    private String returnCondition;

    @Column(name = "unit_price_paise")
    private Long unitPricePaise;

    /** Units ordered. The client always sent this; the DTO used to drop it. */
    @Builder.Default
    private Integer quantity = 1;

    /** Units returned so far across all return events. Never exceeds quantity. */
    @Builder.Default
    private Integer returnedQuantity = 0;

    /** quantity * unitPricePaise, before discount. */
    @Builder.Default
    @Column(name = "gross_value_paise")
    private Long grossValue = 0L;

    /** Line discount. Can never exceed grossValue (database-enforced). */
    @Builder.Default
    private Long discountPaise = 0L;

    /** grossValue - discount. The basis for this line's GST. */
    @Builder.Default
    private Long taxableValuePaise = 0L;

    @Builder.Default
    private Long totalTaxPaise = 0L;

    @Column(name = "hsn_code")
    private String hsnCode;

    private Integer cgstRateBp;
    private Integer sgstRateBp;
    private Integer igstRateBp;
    private Long cgstAmount;
    private Long sgstAmount;
    private Long igstAmount;

    @Builder.Default private Integer cessRateBp = 0;
    @Builder.Default private Long cessAmount = 0L;

    /** Units still eligible to be returned. */
    @Transient
    public int remainingReturnableQuantity() {
        int q = quantity != null ? quantity : 1;
        int r = returnedQuantity != null ? returnedQuantity : 0;
        return Math.max(0, q - r);
    }

    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Builder.Default
    private Boolean archive = Boolean.FALSE;
}
