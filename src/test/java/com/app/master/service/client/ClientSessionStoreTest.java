package com.app.master.service.client;

import com.app.master.service.core.entity.ClientSessionEntity;
import com.app.master.service.repository.client.ClientSessionRepository;
import com.app.master.service.service.client.ClientSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The rotation rules that keep a user signed in across a token change.
 *
 * The repository is a map, so the store's own logic is what is under test:
 * which values are accepted, what a refresh issues, and how the old value
 * is wound down.
 */
class ClientSessionStoreTest {

    private ClientSessionRepository repo;
    private ClientSessionStore store;
    private final Map<String, ClientSessionEntity> rows = new HashMap<>();

    @BeforeEach
    void setUp() {
        repo = mock(ClientSessionRepository.class);
        when(repo.findByToken(anyString())).thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0))));
        when(repo.save(any())).thenAnswer(i -> {
            ClientSessionEntity e = i.getArgument(0);
            rows.put(e.getToken(), e);
            return e;
        });
        store = new ClientSessionStore(repo);
    }

    private ClientSessionEntity session(Instant expiry, Instant accessExpiry) {
        ClientSessionEntity e = ClientSessionEntity.builder()
                .token("old").userId("u1").name("Asha").email("a@x.test").phone("1")
                .expiry(expiry).accessExpiry(accessExpiry).build();
        rows.put("old", e);
        return e;
    }

    @Test
    @DisplayName("A new session is accepted now and must rotate within the access window")
    void createSetsBothLifetimes() {
        String token = store.create("u1", "Asha", "a@x.test", "1");
        ClientSessionEntity e = rows.get(token);
        Instant now = Instant.now();

        assertNotNull(store.get(token), "freshly issued token must be accepted");
        assertTrue(Duration.between(now, e.getAccessExpiry()).toMinutes() <= 10, "access window is 10 minutes");
        assertTrue(Duration.between(now, e.getExpiry()).toDays() >= 6, "login lasts about a week");
    }

    @Test
    @DisplayName("A token past its access window is rejected even though the login is alive")
    void lapsedAccessWindowIsRejected() {
        Instant now = Instant.now();
        session(now.plus(Duration.ofDays(6)), now.minusSeconds(1));
        assertNull(store.get("old"));
    }

    @Test
    @DisplayName("Refresh issues a different token that is accepted immediately")
    void refreshIssuesSuccessor() {
        Instant now = Instant.now();
        session(now.plus(Duration.ofDays(6)), now.plusSeconds(300));

        ClientSessionStore.Refreshed r = store.refresh("old");

        assertNotNull(r);
        assertNotEquals("old", r.token());
        assertNotNull(store.get(r.token()), "successor works at once");
        assertEquals("u1", r.session().userId());
    }

    @Test
    @DisplayName("The old token keeps working through the grace period, then not")
    void oldTokenWindsDownWithinGrace() {
        Instant now = Instant.now();
        ClientSessionEntity old = session(now.plus(Duration.ofDays(6)), now.plusSeconds(300));

        store.refresh("old");

        assertNotNull(store.get("old"), "still accepted right after rotation");
        long secondsLeft = Duration.between(Instant.now(), old.getAccessExpiry()).getSeconds();
        assertTrue(secondsLeft <= ClientSessionStore.ROTATION_GRACE_SECONDS,
                "access capped to the grace period, was " + secondsLeft + "s");
        assertTrue(Duration.between(Instant.now(), old.getExpiry()).getSeconds()
                        <= ClientSessionStore.ROTATION_GRACE_SECONDS,
                "the old value cannot be refreshed again beyond the grace period");
    }

    @Test
    @DisplayName("Rotation never extends the old token")
    void rotationNeverExtendsOldToken() {
        Instant now = Instant.now();
        Instant alreadyLapsed = now.minusSeconds(30);
        ClientSessionEntity old = session(now.plus(Duration.ofDays(6)), alreadyLapsed);

        store.refresh("old");

        assertEquals(alreadyLapsed, old.getAccessExpiry(),
                "a lapsed access window must stay lapsed, not be re-opened for the grace period");
    }

    @Test
    @DisplayName("Refresh does not extend the login: the successor inherits the original expiry")
    void refreshInheritsLoginExpiry() {
        Instant now = Instant.now();
        Instant loginEnds = now.plus(Duration.ofDays(3));
        session(loginEnds, now.plusSeconds(300));

        ClientSessionStore.Refreshed r = store.refresh("old");

        assertEquals(loginEnds, rows.get(r.token()).getExpiry());
    }

    @Test
    @DisplayName("An idle user whose access window lapsed can still refresh while the login is alive")
    void idleUserCanRefresh() {
        Instant now = Instant.now();
        session(now.plus(Duration.ofDays(6)), now.minus(Duration.ofHours(1)));

        assertNull(store.get("old"), "the old value itself is no longer accepted for requests");
        assertNotNull(store.refresh("old"), "but it can be exchanged for a working one");
    }

    @Test
    @DisplayName("Once the login has ended, refresh is refused")
    void endedLoginCannotRefresh() {
        Instant now = Instant.now();
        session(now.minusSeconds(1), now.minusSeconds(1));

        assertNull(store.refresh("old"));
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("An unknown token cannot be refreshed")
    void unknownTokenCannotRefresh() {
        assertNull(store.refresh("nope"));
    }

    @Test
    @DisplayName("Refresh persists both the successor and the wound-down original")
    void refreshSavesBothRows() {
        Instant now = Instant.now();
        session(now.plus(Duration.ofDays(6)), now.plusSeconds(300));

        store.refresh("old");

        ArgumentCaptor<ClientSessionEntity> saved = ArgumentCaptor.forClass(ClientSessionEntity.class);
        verify(repo, times(2)).save(saved.capture());
        assertTrue(saved.getAllValues().stream().anyMatch(e -> "old".equals(e.getToken())));
        assertTrue(saved.getAllValues().stream().anyMatch(e -> !"old".equals(e.getToken())));
    }
}
