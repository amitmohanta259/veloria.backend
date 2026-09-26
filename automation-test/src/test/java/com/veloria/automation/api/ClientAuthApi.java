package com.veloria.automation.api;

import java.util.Map;

/** /client auth endpoints. */
public final class ClientAuthApi {
    private ClientAuthApi() {}

    public static Http.Response loginWithPassword(String email, String password) {
        return Http.post("/client/login/password", Map.of("email", email, "password", password), null);
    }

    public static Http.Response refresh(String token) {
        return Http.post("/client/session/refresh", null, token);
    }
}
