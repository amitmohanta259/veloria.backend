package com.veloria.automation.api;

public final class ReturnsApi {
    private ReturnsApi() {}

    public static Http.Response stats(String token)    { return Http.get("/returns/stats", token); }
    public static Http.Response requests(String token) { return Http.get("/returns/requests?page=0&pageSize=100", token); }
    public static Http.Response reasons(String token)  { return Http.get("/returns/reasons", token); }
}
