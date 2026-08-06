package com.app.master.service.core.entity;

import com.app.master.service.core.dto.Base;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "sales_order")
public class SalesOrderEntity extends Base {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    private String orderCode;
    private UUID productUuid;
    private String productName;
    private String skuId;
    private Integer quantity;
    private Long unitPrice;
    private Long totalValue;
    private String currency;
    private String customerName;
    private String customerEmail;
    private String status;
    private Integer returnWindowDays;
    private Instant orderPlacedAt;

    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Builder.Default
    private Boolean archive = Boolean.FALSE;
}
