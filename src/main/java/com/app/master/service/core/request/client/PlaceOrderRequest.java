package com.app.master.service.core.request.client;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class PlaceOrderRequest {

    @NotBlank
    private String deliveryLocation;

    private String currency = "INR";

    /**
     * Opaque reference identifying one checkout attempt.
     *
     * Optional for now so clients that have not been updated keep working
     * exactly as before; when present it makes the request safe to retry.
     * Must be regenerated for a new checkout and reused unchanged on a retry.
     */
    @Size(max = 64, message = "clientOrderReference must be at most 64 characters")
    private String clientOrderReference;

    @Valid
    @NotEmpty
    private List<OrderItemRequest> items;

    @Data
    public static class OrderItemRequest {
        private UUID productUuid;
        private String selectedDimension;
        private String size;

        /**
         * Units ordered. The client has always sent this; the field was missing
         * here, so Jackson dropped it and every line was billed as one unit.
         */
        @Min(value = 1, message = "quantity must be at least 1")
        private Integer quantity = 1;
    }
}
