package com.app.master.service.core.response.client;

import java.util.List;

public record CartGstPreviewResponse(
        String supplyType,
        String buyerStateCode,
        String sellerStateCode,
        long subtotalPaise,
        long cgstAmount,
        long sgstAmount,
        long igstAmount,
        long totalGst,
        long grandTotal,
        List<ItemGst> items
) {
    public record ItemGst(
            String productUuid,
            String hsnCode,
            int quantity,
            long unitPricePaise,
            long taxableValuePaise,
            int cgstRateBp,
            int sgstRateBp,
            int igstRateBp,
            long cgstAmount,
            long sgstAmount,
            long igstAmount,
            long totalTax
    ) {}
}
