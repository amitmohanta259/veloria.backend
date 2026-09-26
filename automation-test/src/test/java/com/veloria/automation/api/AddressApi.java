package com.veloria.automation.api;

import java.util.Map;

/** /client/address-book — the buyer's delivery addresses. */
public final class AddressApi {
    private AddressApi() {}

    public static Http.Response save(String token, String stateCode, String pincode, boolean isDefault) {
        return Http.post("/client/address-book", Map.of(
                "receiverName", "Automation Buyer",
                "phone", "9000000000",
                "address", "12 Automation Lane",
                "city", "Test City",
                "stateCode", stateCode,
                "pincode", pincode,
                "isDefault", isDefault), token);
    }
}
