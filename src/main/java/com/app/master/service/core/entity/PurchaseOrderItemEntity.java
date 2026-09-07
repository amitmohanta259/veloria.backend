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
@Table(name = "purchase_order_item")
public class PurchaseOrderItemEntity extends Base {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    private Long purchaseOrderId;
    private String productName;
    private String skuId;
    private Long unitCost;
    private Integer quantity;
    private Long lineTotal;

    /** Prefills the HSN when a vendor invoice is recorded from this order. */
    private String hsnCode;
}
