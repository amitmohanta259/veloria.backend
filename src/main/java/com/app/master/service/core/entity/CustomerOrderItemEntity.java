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
    private String reasonForReturn;

    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Builder.Default
    private Boolean archive = Boolean.FALSE;
}
