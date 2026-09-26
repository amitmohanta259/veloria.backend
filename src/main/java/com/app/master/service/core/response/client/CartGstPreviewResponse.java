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
        /**
         * What cash on delivery would cost, computed here rather than in the
         * browser. The checkout screen used to add a ₹50 literal of its own; the
         * moment the charge carries tax that literal is wrong, and a customer shown
         * one figure and charged another is a defect however small the difference.
         */
        CodCharge codCharge,
        List<ItemGst> items
) {

    /**
     * The cash-on-delivery charge, as the server computes it.
     *
     * <p>{@code taxResolution} is passed through deliberately: a client that shows
     * a tax line must be able to tell "no tax applies" from "the tax is not known",
     * and so must anyone reading this response while the rate remains undecided.
     */
    public record CodCharge(
            long feePaise,
            long taxPaise,
            Integer taxRateBp,
            String taxResolution,
            /** Grand total plus the charge and its tax: what a COD customer pays. */
            long totalPaise
    ) {}
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
