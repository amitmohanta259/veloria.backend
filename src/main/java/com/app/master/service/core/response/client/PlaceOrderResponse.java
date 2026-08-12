package com.app.master.service.core.response.client;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class PlaceOrderResponse {
    private UUID orderUuid;
    private String orderCode;
}
