package com.veloria.automation.api;

import java.util.Map;

public final class FavouriteApi {
    private FavouriteApi() {}

    public static Http.Response list(String token)  { return Http.get("/client/favourites", token); }
    public static Http.Response uuids(String token) { return Http.get("/client/favourites/uuids", token); }
    public static Http.Response add(String token, String productUuid) {
        return Http.post("/client/favourites/add", Map.of("productUuid", productUuid), token);
    }
    public static Http.Response remove(String token, String productUuid) {
        return Http.delete("/client/favourites/" + productUuid, token);
    }
}
