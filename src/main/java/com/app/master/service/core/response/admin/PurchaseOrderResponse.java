package com.app.master.service.core.response.admin;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PurchaseOrderResponse {

    private UUID uuid;
    private String poCode;
    private UUID supplierUuid;
    private String supplierName;
    private String supplierCode;
    private String status;
    private Long totalValue;
    private String currency;
    private String paymentTerms;
    private String notes;
    private String invoiceUrl;
    private String created;
    private List<PurchaseOrderItemResponse> items;
}
