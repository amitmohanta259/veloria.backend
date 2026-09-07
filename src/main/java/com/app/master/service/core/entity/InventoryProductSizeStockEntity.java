package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "inventory_product_size_stock")
public class InventoryProductSizeStockEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long productId;
    private String size;
    private Long initialStock;

    @Builder.Default
    private Boolean archive = Boolean.FALSE;
}
