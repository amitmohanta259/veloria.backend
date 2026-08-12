package com.app.master.service.service.client;

import lombok.Builder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ClientSessionStore {

    private static final long SESSION_TTL_SECONDS = 60L * 60 * 24 * 7; // 7 days

    @Builder
    public record SessionData(String userId, String name, String email, String phone, Instant expiry) {}

    private final Map<String, SessionData> store = new ConcurrentHashMap<>();

    public String create(String userId, String name, String email, String phone) {
        String token = UUID.randomUUID().toString();
        store.put(token, SessionData.builder()
                .userId(userId).name(name).email(email).phone(phone)
                .expiry(Instant.now().plusSeconds(SESSION_TTL_SECONDS))
                .build());
        return token;
    }

    public SessionData get(String token) {
        SessionData s = store.get(token);
        if (s == null || Instant.now().isAfter(s.expiry())) {
            store.remove(token);
            return null;
        }
        return s;
    }

    public void invalidate(String token) {
        store.remove(token);
    }

}
