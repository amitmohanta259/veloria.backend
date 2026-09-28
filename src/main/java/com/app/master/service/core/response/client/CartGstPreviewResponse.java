package com.app.master.service.core.response.client;

import java.util.List;

public record CartGstPreviewResponse(
        /** INTRA_STATE or INTER_STATE; null when the place of supply is unknown. */
        String supplyType,
        String buyerStateCode,
        String sellerStateCode,
        /** Price data, so it is always known — tax plays no part in it. */
        long subtotalPaise,
        /**
         * The tax, or {@code null} when it is not yet determinable.
         *
         * <p>Null rather than 0. A shopper who has not given a delivery address has
         * no place of supply, so it cannot be known whether the supply attracts
         * CGST and SGST or IGST — which is not the same statement as "this supply
         * is taxed at 0%", and a client must not be able to read one as the other.
         * {@link #gstResolved} is the field to key off; {@link #taxResolution} says
         * why when it is false.
         */
        Long cgstAmount,
        Long sgstAmount,
        Long igstAmount,
        Long totalGst,
        /** Subtotal plus tax, and so also null while the tax is undetermined. */
        Long grandTotal,
        /** False when the tax could not be determined; the amounts above are then null. */
        boolean gstResolved,
        /** RULE_APPLIED, or NO_PLACE_OF_SUPPLY when there is no delivery address yet. */
        String taxResolution,
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
     * <p>Null when cash on delivery is switched off entirely.
     *
     * <p>{@code available} is the field a client must key off. It is false when the
     * charge cannot be priced — no service code configured, no tax basis, or no
     * active rule for the code — and in that case the amounts are not a quote and
     * COD must not be offered. {@code taxResolution} says which of those it was, so
     * the reason is diagnosable rather than a bare refusal.
     *
     * <p>Both the per-head amounts and the combined total are carried, because a
     * client showing a tax breakdown must be able to tell CGST + SGST from IGST
     * without deciding the supply type itself.
     */
    public record CodCharge(
            boolean available,
            String serviceCode,
            String serviceName,
            String sac,
            long feePaise,
            long taxablePaise,
            long cgstPaise,
            long sgstPaise,
            long igstPaise,
            long taxPaise,
            Integer taxRateBp,
            boolean taxInclusive,
            /** The state code the charge is taxed against — what decided CGST+SGST vs IGST. */
            String placeOfSupply,
            String taxResolution,
            /** Grand total plus the charge and its tax: what a COD customer pays. */
            long totalPaise
    ) {}
    /**
     * One line of the cart.
     *
     * <p>The quantity and the money the shopper is being asked for are always
     * known. The rates and tax amounts are null when the tax is not determinable,
     * for the same reason as on the enclosing record: an undetermined rate is not
     * a 0% rate.
     */
    public record ItemGst(
            String productUuid,
            String hsnCode,
            int quantity,
            long unitPricePaise,
            long taxableValuePaise,
            Integer cgstRateBp,
            Integer sgstRateBp,
            Integer igstRateBp,
            Long cgstAmount,
            Long sgstAmount,
            Long igstAmount,
            Long totalTax
    ) {}
}
