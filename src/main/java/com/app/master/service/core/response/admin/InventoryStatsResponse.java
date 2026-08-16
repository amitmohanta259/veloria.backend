package com.app.master.service.core.response.admin;

import lombok.*;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class InventoryStatsResponse {
    private long totalStockValue;
    private String currency;
    private long lowOnStockCount;
    private long outOfStockCount;
    private String topCategory;
    private double topCategoryShare;
    private long grossSalesValue;
    private long damageLossValue;
    private long transitLossValue;
}
