package com.veloria.automation.api;

import java.util.HashMap;
import java.util.Map;

/** /client/bag — the shopping bag. */
public final class BagApi {
    private BagApi() {}

    public static Http.Response get(String token) { return Http.get("/client/bag", token); }

    public static Http.Response add(String token, String productUuid, int quantity, String size) {
        Map<String, Object> body = new HashMap<>();
        body.put("productUuid", productUuid);
        body.put("quantity", quantity);
        body.put("size", size);
        return Http.post("/client/bag/add", body, token);
    }

    public static Http.Response updateQuantity(String token, String productUuid, int quantity) {
        return Http.put("/client/bag/" + productUuid + "/quantity?quantity=" + quantity, null, token);
    }

    public static Http.Response remove(String token, String productUuid) {
        return Http.delete("/client/bag/" + productUuid, token);
    }

    public static Http.Response gstPreview(String token) { return Http.get("/client/bag/gst-preview", token); }
}
