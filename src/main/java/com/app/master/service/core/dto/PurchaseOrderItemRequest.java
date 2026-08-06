package com.app.master.service.core.dto;

import lombok.*;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PurchaseOrderItemRequest {

    private String productName;
    private String skuId;
    private Double unitCost;
    private Integer quantity;
}
