package com.app.master.service.core.response.client;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class OrderConfirmationResponse {

    private String orderCode;
    private String customerName;
    private String customerEmail;
    private String status;
    private String currency;
    private Long totalValue;
    private Long taxableValue;
    private Long cgstAmount;
    private Long sgstAmount;
    private Long igstAmount;
    private Long totalTaxAmount;
    private String buyerStateCode;
    private String sellerStateCode;
    private String placeOfSupply;
    private Instant orderPlacedAt;

    private List<OrderItemDetail> items;
    private DeliveryAddress deliveryAddress;
    private SenderAddress senderAddress;

    @Data
    @Builder
    public static class OrderItemDetail {
        private String productName;
        private String skuId;
        private String size;
        private String selectedDimension;
        private Long unitPrice;
        private String hsnCode;
        private Integer cgstRateBp;
        private Integer sgstRateBp;
        private Integer igstRateBp;
        private Long cgstAmount;
        private Long sgstAmount;
        private Long igstAmount;
        private String currency;
        private String imageUrl;
    }

    @Data
    @Builder
    public static class DeliveryAddress {
        private String receiverName;
        private String phone;
        private String address;
    }

    @Data
    @Builder
    public static class SenderAddress {
        private String companyName;
        private String companyAddress;
        private String registeredPhone;
        private String registeredEmail;
    }
}
