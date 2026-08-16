package com.app.master.service.core.response.admin;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnsLogResponse {

    private UUID itemUuid;
    private String orderCode;
    private UUID productUuid;
    private String productName;
    private String skuId;
    private Long unitPrice;
    private String currency;
    private String customerName;
    private String customerEmail;
    private String status;
    private String selectedDimension;
    private String reasonForReturn;
    private String returnCondition;
    private Instant orderPlacedAt;
}
