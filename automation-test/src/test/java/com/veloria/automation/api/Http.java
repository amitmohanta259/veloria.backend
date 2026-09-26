package com.veloria.automation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.veloria.automation.support.Config;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * The one place HTTP is spoken. Every API client builds on this, so a step
 * definition never assembles a request itself.
 *
 * Responses expose the envelope the backend always returns
 * ({code, message, data}) so assertions can say "data.orderCode" rather
 * than walking JSON by hand.
 */
public final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private Http() {}

    public record Response(int status, JsonNode body, String raw) {
        public String message() { return body != null && body.hasNonNull("message") ? body.get("message").asText() : ""; }
        public JsonNode data()  { return body != null ? body.get("data") : null; }
        public boolean ok()     { return status >= 200 && status < 300; }
    }

    public static Response get(String path, String bearer) {
        return send(base(path).GET(), bearer);
    }

    public static Response post(String path, Object body, String bearer) {
        return send(base(path).POST(publisher(body)).header("Content-Type", "application/json"), bearer);
    }

    public static Response put(String path, Object body, String bearer) {
        return send(base(path).PUT(publisher(body)).header("Content-Type", "application/json"), bearer);
    }

    /** Order status changes are a PATCH, which HttpRequest only offers via method(). */
    public static Response patch(String path, Object body, String bearer) {
        return send(base(path).method("PATCH", publisher(body)).header("Content-Type", "application/json"), bearer);
    }

    public static Response delete(String path, String bearer) {
        return send(base(path).DELETE(), bearer);
    }

    public static ObjectMapper json() { return JSON; }

    // ── internals ────────────────────────────────────────────────────────────

    private static HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create(Config.apiUrl() + path))
                .timeout(Duration.ofSeconds(30));
    }

    private static HttpRequest.BodyPublisher publisher(Object body) {
        try {
            if (body == null) return HttpRequest.BodyPublishers.noBody();
            if (body instanceof String s) return HttpRequest.BodyPublishers.ofString(s);
            return HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        } catch (IOException e) {
            throw new IllegalArgumentException("Unserialisable request body", e);
        }
    }

    private static Response send(HttpRequest.Builder b, String bearer) {
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        try {
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = null;
            if (r.body() != null && !r.body().isBlank()) {
                try { node = JSON.readTree(r.body()); } catch (IOException ignored) { /* non-JSON body */ }
            }
            return new Response(r.statusCode(), node, r.body());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("HTTP call failed: " + b.build().uri(), e);
        }
    }

    /** Small helper for query strings built from a map. */
    public static String query(Map<String, ?> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (v == null) return;
            sb.append(sb.length() == 0 ? "?" : "&").append(k).append('=').append(v);
        });
        return sb.toString();
    }
}
