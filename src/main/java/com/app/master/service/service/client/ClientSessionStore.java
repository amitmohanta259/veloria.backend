package com.app.master.service.service.client;

import com.app.master.service.core.entity.ClientSessionEntity;
import com.app.master.service.repository.client.ClientSessionRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Client sessions, with rotating tokens.
 *
 * A login lasts {@link #SESSION_TTL_SECONDS}. Within it, each token value is
 * only accepted for {@link #ACCESS_TTL_SECONDS} and must then be exchanged for
 * a successor via {@link #refresh}. The old value stays usable for
 * {@link #ROTATION_GRACE_SECONDS} after rotation so requests already in flight,
 * and other tabs that have not yet picked up the new value, do not fail.
 *
 * A refresh never extends the login: the successor inherits the original
 * {@code expiry}. A token whose access window has lapsed can still be
 * refreshed while the login is alive, which is what keeps a user who was idle
 * for an hour signed in rather than bounced to the login page.
 */
@Component
@RequiredArgsConstructor
public class ClientSessionStore {

    /** How long a login lasts. */
    public static final long SESSION_TTL_SECONDS = 60L * 60 * 24 * 7; // 7 days
    /** How long one token value is accepted before it must be rotated. */
    public static final long ACCESS_TTL_SECONDS = 60L * 10;            // 10 minutes
    /** How long the previous value keeps working after rotation. */
    public static final long ROTATION_GRACE_SECONDS = 60L;

    private final ClientSessionRepository sessionRepository;

    @Builder
    public record SessionData(String userId, String name, String email, String phone, Instant expiry) {}

    /** A rotated session: the new token and the data it carries. */
    public record Refreshed(String token, SessionData session) {}

    @Transactional
    public String create(String userId, String name, String email, String phone) {
        Instant now = Instant.now();
        String token = UUID.randomUUID().toString();
        sessionRepository.save(ClientSessionEntity.builder()
                .token(token)
                .userId(userId)
                .name(name)
                .email(email)
                .phone(phone)
                .expiry(now.plusSeconds(SESSION_TTL_SECONDS))
                .accessExpiry(now.plusSeconds(ACCESS_TTL_SECONDS))
                .build());
        return token;
    }

    /** The session behind a token, or null if the value is no longer accepted. */
    public SessionData get(String token) {
        Instant now = Instant.now();
        return sessionRepository.findByToken(token)
                .filter(s -> now.isBefore(s.getExpiry()) && now.isBefore(s.getAccessExpiry()))
                .map(ClientSessionStore::toData)
                .orElse(null);
    }

    /**
     * Exchanges a token for a successor, or returns null if the login has ended.
     *
     * The old value is retired rather than deleted: its lifetimes are capped to
     * the grace period, never extended, so it winds down without cutting off
     * work that started before the rotation.
     */
    @Transactional
    public Refreshed refresh(String token) {
        Instant now = Instant.now();
        ClientSessionEntity old = sessionRepository.findByToken(token)
                .filter(s -> now.isBefore(s.getExpiry()))
                .orElse(null);
        if (old == null) return null;

        ClientSessionEntity next = sessionRepository.save(ClientSessionEntity.builder()
                .token(UUID.randomUUID().toString())
                .userId(old.getUserId())
                .name(old.getName())
                .email(old.getEmail())
                .phone(old.getPhone())
                .expiry(old.getExpiry())                          // the login is not extended
                .accessExpiry(now.plusSeconds(ACCESS_TTL_SECONDS))
                .build());

        Instant grace = now.plusSeconds(ROTATION_GRACE_SECONDS);
        if (old.getExpiry().isAfter(grace)) old.setExpiry(grace);
        if (old.getAccessExpiry().isAfter(grace)) old.setAccessExpiry(grace);
        sessionRepository.save(old);

        return new Refreshed(next.getToken(), toData(next));
    }

    @Transactional
    public void invalidate(String token) {
        sessionRepository.deleteByToken(token);
    }

    // Clean up expired sessions once per day
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeExpired() {
        sessionRepository.deleteExpiredSessions(Instant.now());
    }

    private static SessionData toData(ClientSessionEntity s) {
        return SessionData.builder()
                .userId(s.getUserId())
                .name(s.getName())
                .email(s.getEmail())
                .phone(s.getPhone())
                .expiry(s.getExpiry())
                .build();
    }
}
