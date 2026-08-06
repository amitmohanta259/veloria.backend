package com.app.master.service.core.response.admin;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PurchaseOrderItemResponse {

    private UUID uuid;
    private String productName;
    private String skuId;
    private Long unitCost;
    private Integer quantity;
    private Long lineTotal;
}
