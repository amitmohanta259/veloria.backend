package com.app.master.service.gst;

import com.app.master.service.core.entity.GstStateMasterEntity;
import com.app.master.service.core.entity.UserAddressEntity;
import com.app.master.service.repository.admin.GstStateMasterRepository;
import com.app.master.service.repository.client.UserAddressRepository;
import com.app.master.service.service.admin.PlaceOfSupplyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The fix for the audit's headline finding: buyer_state_code was NULL on 100%
 * of orders because the client posts a free-text address and the old resolver
 * only accepted a UUID.
 */
class PlaceOfSupplyResolverTest {

    private static final String LIVE_ADDRESS = "123 MG Road, Bhubaneswar, Odisha 751001";

    private UserAddressRepository addressRepo;
    private GstStateMasterRepository stateRepo;
    private PlaceOfSupplyResolver resolver;

    @BeforeEach
    void setUp() {
        addressRepo = mock(UserAddressRepository.class);
        stateRepo = mock(GstStateMasterRepository.class);
        resolver = new PlaceOfSupplyResolver(addressRepo, stateRepo);

        when(stateRepo.findAllByActiveTrueOrderByStateName()).thenReturn(List.of(
                state("21", "Odisha"),
                state("27", "Maharashtra"),
                state("09", "Uttar Pradesh"),
                state("37", "Andhra Pradesh")));
        when(addressRepo.findByUuid(any())).thenReturn(Optional.empty());
        when(addressRepo.findByUserIdAndArchiveFalseOrderByIsDefaultDescCreatedAtAsc(anyString()))
                .thenReturn(List.of());
    }

    private GstStateMasterEntity state(String code, String name) {
        return GstStateMasterEntity.builder().stateCode(code).stateName(name).active(true).build();
    }

    @Test
    @DisplayName("An address UUID resolves from the address book")
    void resolvesByUuid() {
        UUID id = UUID.randomUUID();
        UserAddressEntity addr = new UserAddressEntity();
        addr.setStateCode("27");
        when(addressRepo.findByUuid(id)).thenReturn(Optional.of(addr));

        var r = resolver.resolve(id.toString(), "cust-1");

        assertTrue(r.resolved());
        assertEquals("27", r.stateCode());
        assertEquals("ADDRESS_UUID", r.source());
    }

    @Test
    @DisplayName("Free text matching a saved address resolves from the address book")
    void resolvesBySavedAddressText() {
        UserAddressEntity addr = new UserAddressEntity();
        addr.setAddress(LIVE_ADDRESS);
        addr.setStateCode("21");
        when(addressRepo.findByUserIdAndArchiveFalseOrderByIsDefaultDescCreatedAtAsc("cust-1"))
                .thenReturn(List.of(addr));

        var r = resolver.resolve(LIVE_ADDRESS, "cust-1");

        assertTrue(r.resolved());
        assertEquals("21", r.stateCode());
        assertEquals("SAVED_ADDRESS_TEXT", r.source());
    }

    @Test
    @DisplayName("The live free-text address resolves by state name")
    void resolvesByStateName() {
        var r = resolver.resolve(LIVE_ADDRESS, "cust-unknown");

        assertTrue(r.resolved(), "The address that produced NULL in production must now resolve");
        assertEquals("21", r.stateCode());
        assertEquals("STATE_NAME", r.source());
    }

    @Test
    @DisplayName("The longest matching state name wins over a substring")
    void longestStateNameWins() {
        var r = resolver.resolve("Plot 5, Vijayawada, Andhra Pradesh 520001", "cust-1");
        assertEquals("37", r.stateCode(), "Andhra Pradesh must beat a bare 'Pradesh' match");
    }

    @Test
    @DisplayName("A PIN code resolves when no state name is present")
    void resolvesByPincode() {
        var r = resolver.resolve("Flat 2B, Some Lane, 751024", "cust-1");

        assertTrue(r.resolved());
        assertEquals("21", r.stateCode(), "75xxxx is Odisha");
        assertEquals("PINCODE", r.source());
    }

    @Test
    @DisplayName("An unresolvable address reports unresolved rather than guessing")
    void unresolvableIsReported() {
        var r = resolver.resolve("somewhere unhelpful", "cust-1");

        assertFalse(r.resolved());
        assertNull(r.stateCode());
        assertEquals("UNRESOLVED", r.source());
    }

    @Test
    @DisplayName("Blank input is unresolved")
    void blankIsUnresolved() {
        assertFalse(resolver.resolve(null, "cust-1").resolved());
        assertFalse(resolver.resolve("   ", "cust-1").resolved());
    }
}
