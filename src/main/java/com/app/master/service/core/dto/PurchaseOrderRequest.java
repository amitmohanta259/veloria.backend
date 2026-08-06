package com.app.master.service.core.dto;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PurchaseOrderRequest {

    private UUID supplierUuid;
    private String currency;
    private String paymentTerms;
    private String notes;
    private List<PurchaseOrderItemRequest> items;
}
