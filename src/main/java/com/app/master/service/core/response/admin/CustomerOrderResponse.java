package com.app.master.service.core.response.admin;

import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerOrderResponse {

    private UUID uuid;
    private String orderCode;
    private String customerId;
    private String customerName;
    private String customerEmail;
    private String deliveryLocation;
    private Long totalValue;
    private String currency;
    private String status;
    private Instant orderPlacedAt;
    private List<CustomerOrderItemResponse> items;
}
