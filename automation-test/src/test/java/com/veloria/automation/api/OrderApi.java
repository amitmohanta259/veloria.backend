package com.veloria.automation.api;

import java.util.List;
import java.util.Map;

/** /client/order — placing and reading orders. */
public final class OrderApi {
    private OrderApi() {}

    public static Http.Response place(String token, Object body) {
        return Http.post("/client/order/place", body, token);
    }

    /** A well-formed request for one product. */
    public static Map<String, Object> request(String deliveryLocation, String productUuid, int quantity) {
        return Map.of(
                "deliveryLocation", deliveryLocation,
                "currency", "INR",
                "items", List.of(Map.of("productUuid", productUuid, "quantity", quantity)));
    }

    public static Http.Response history(String token)                { return Http.get("/client/order/history", token); }
    public static Http.Response detail(String token, String code)   { return Http.get("/client/order/" + code, token); }
    public static Http.Response requestReturn(String token, String code, Map<String, String> body) {
        return Http.post("/client/order/" + code + "/return", body, token);
    }
}
