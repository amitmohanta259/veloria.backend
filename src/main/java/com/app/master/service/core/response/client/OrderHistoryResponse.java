package com.app.master.service.core.response.client;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class OrderHistoryResponse {

    private String orderCode;
    private String status;
    private Long totalValue;
    private String currency;
    private Instant orderPlacedAt;
    private Instant deliveredAt;
    private List<OrderItemSummary> items;

    @Getter
    @Builder
    public static class OrderItemSummary {
        private UUID productUuid;
        private String productName;
        private String skuId;
        private String size;
        private String selectedDimension;
        private Integer quantity;
        private Long unitPrice;
    }
}
