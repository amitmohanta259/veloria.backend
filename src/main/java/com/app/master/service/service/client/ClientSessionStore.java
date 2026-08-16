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

@Component
@RequiredArgsConstructor
public class ClientSessionStore {

    private static final long SESSION_TTL_SECONDS = 60L * 60 * 24 * 7; // 7 days

    private final ClientSessionRepository sessionRepository;

    @Builder
    public record SessionData(String userId, String name, String email, String phone, Instant expiry) {}

    @Transactional
    public String create(String userId, String name, String email, String phone) {
        String token = UUID.randomUUID().toString();
        sessionRepository.save(ClientSessionEntity.builder()
                .token(token)
                .userId(userId)
                .name(name)
                .email(email)
                .phone(phone)
                .expiry(Instant.now().plusSeconds(SESSION_TTL_SECONDS))
                .build());
        return token;
    }

    public SessionData get(String token) {
        return sessionRepository.findByToken(token)
                .filter(s -> Instant.now().isBefore(s.getExpiry()))
                .map(s -> SessionData.builder()
                        .userId(s.getUserId())
                        .name(s.getName())
                        .email(s.getEmail())
                        .phone(s.getPhone())
                        .expiry(s.getExpiry())
                        .build())
                .orElse(null);
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
}
