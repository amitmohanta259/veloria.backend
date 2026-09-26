package com.veloria.automation.api;

/** Public catalogue endpoints — none of these need a token. */
public final class ProductApi {
    private ProductApi() {}

    public static Http.Response newIn(String bearer)                 { return Http.get("/client/products/new-in", bearer); }
    /** The whole catalogue. Unlike new-in this has no time window, so it is stable for fixtures. */
    public static Http.Response all()                                 { return Http.get("/client/products/all", null); }
    public static Http.Response popular(int page, int size)           { return Http.get("/client/products/popular?page=" + page + "&size=" + size, null); }
    public static Http.Response byUuid(String uuid)                   { return Http.get("/client/products/" + uuid, null); }
    public static Http.Response sizes(String uuid)                    { return Http.get("/client/products/" + uuid + "/sizes", null); }
    public static Http.Response categories(String bearer)             { return Http.get("/client/categories", bearer); }
}
