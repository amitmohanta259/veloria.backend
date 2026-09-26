package com.veloria.automation.api;

import java.util.Map;

/** /gst — rules, HSN master and the calculator. All VIEW_GST-protected. */
public final class GstApi {
    private GstApi() {}

    public static Http.Response rules(String token)     { return Http.get("/gst/rules", token); }
    public static Http.Response hsnSearch(String token) { return Http.get("/gst/hsn/search", token); }

    public static Http.Response calculate(String token, String hsn, long pricePaise,
                                          String buyerState, String sellerState, String date) {
        return Http.post("/gst/calculate", Map.of(
                "hsnCode", hsn,
                "pricePaise", pricePaise,
                "buyerStateCode", buyerState,
                "sellerStateCode", sellerState,
                "effectiveDate", date), token);
    }
}
