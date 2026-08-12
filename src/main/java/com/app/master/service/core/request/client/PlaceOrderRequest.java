package com.app.master.service.core.request.client;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class PlaceOrderRequest {

    @NotBlank
    private String deliveryLocation;

    private String currency = "INR";

    @Valid
    @NotEmpty
    private List<OrderItemRequest> items;

    @Data
    public static class OrderItemRequest {
        private UUID productUuid;
        private String selectedDimension;
    }
}
