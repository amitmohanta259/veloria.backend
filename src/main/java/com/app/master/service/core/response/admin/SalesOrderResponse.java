package com.app.master.service.core.response.admin;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesOrderResponse {

    private UUID uuid;
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
    private String selectedDimension;
    private Integer returnWindowDays;
    private Instant orderPlacedAt;
}
