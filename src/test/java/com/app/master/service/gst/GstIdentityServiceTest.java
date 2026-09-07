package com.app.master.service.gst;

import com.app.master.service.core.entity.BusinessDetailsEntity;
import com.app.master.service.repository.admin.BusinessDetailsRepository;
import com.app.master.service.repository.admin.GstRegistrationRepository;
import com.app.master.service.repository.admin.GstStateMasterRepository;
import com.app.master.service.repository.admin.OrganizationRepository;
import com.app.master.service.service.admin.GstIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * GSTIN validation and the seller-state derivation that fixes the audit's
 * finding of GSTIN 21... (Odisha) stored alongside seller_state_code 20.
 */
class GstIdentityServiceTest {

    private BusinessDetailsRepository businessRepo;
    private GstStateMasterRepository stateRepo;
    private GstRegistrationRepository registrationRepo;
    private OrganizationRepository organizationRepo;
    private GstIdentityService service;

    @BeforeEach
    void setUp() {
        businessRepo = mock(BusinessDetailsRepository.class);
        stateRepo = mock(GstStateMasterRepository.class);
        registrationRepo = mock(GstRegistrationRepository.class);
        organizationRepo = mock(OrganizationRepository.class);
        // No registration configured: these tests exercise the business_details
        // fallback path, which is what an un-migrated deployment still uses.
        when(organizationRepo.findFirstByActiveTrueOrderByIdAsc()).thenReturn(Optional.empty());
        service = new GstIdentityService(businessRepo, stateRepo, registrationRepo, organizationRepo);
    }

    @Test
    @DisplayName("Valid GSTIN formats are accepted")
    void validGstin() {
        assertTrue(service.isValidGstin("21AABCU9603R1ZX"));
        assertTrue(service.isValidGstin("27AAPFU0939F1ZV"));
    }

    @Test
    @DisplayName("Malformed GSTINs are rejected")
    void invalidGstin() {
        assertFalse(service.isValidGstin(null));
        assertFalse(service.isValidGstin(""));
        assertFalse(service.isValidGstin("21AABCU9603R1Z"));    // too short
        assertFalse(service.isValidGstin("21AABCU9603R1ZXY"));  // too long
        assertFalse(service.isValidGstin("2AABCU9603R1ZXX"));   // bad state digits
        assertFalse(service.isValidGstin("21aabcu9603r1zx".toUpperCase().replace('Z', 'Q'))); // no Z in slot 14
    }

    @Test
    @DisplayName("State code is read from the GSTIN's first two characters")
    void stateCodeFromGstin() {
        assertEquals("21", service.stateCodeOf("21AABCU9603R1ZX")); // Odisha
        assertEquals("27", service.stateCodeOf("27AAPFU0939F1ZV")); // Maharashtra
        assertNull(service.stateCodeOf("not-a-gstin"));
    }

    @Test
    @DisplayName("Seller state comes from the GSTIN, not the stored column, when they disagree")
    void sellerStateDerivedFromGstin() {
        BusinessDetailsEntity biz = new BusinessDetailsEntity();
        biz.setGstNumber("21AABCU9603R1ZX"); // Odisha
        biz.setSellerStateCode("20");        // Jharkhand — the bug found in the audit
        when(businessRepo.findFirstByArchiveFalseOrderByIdAsc()).thenReturn(Optional.of(biz));

        assertEquals("21", service.sellerStateCode(),
                "GSTIN is authoritative when the stored state code disagrees");
    }

    @Test
    @DisplayName("Stored state code is used only when the GSTIN is unusable")
    void fallsBackToStoredWhenGstinInvalid() {
        BusinessDetailsEntity biz = new BusinessDetailsEntity();
        biz.setGstNumber("garbage");
        biz.setSellerStateCode("20");
        when(businessRepo.findFirstByArchiveFalseOrderByIdAsc()).thenReturn(Optional.of(biz));

        assertEquals("20", service.sellerStateCode());
    }

    @Test
    @DisplayName("Same state is intra-state; different states are inter-state")
    void interStateClassification() {
        assertFalse(service.isInterState("21", "21"));
        assertTrue(service.isInterState("27", "21"));
        assertEquals("INTRA_STATE", service.supplyType("21", "21"));
        assertEquals("INTER_STATE", service.supplyType("27", "21"));
    }

    @Test
    @DisplayName("An unknown place of supply throws rather than defaulting to intra-state")
    void unknownPlaceOfSupplyThrows() {
        // This is the core fix: the old code returned false (intra-state) here,
        // silently charging CGST+SGST on possibly inter-state supplies.
        assertThrows(IllegalStateException.class, () -> service.isInterState(null, "21"));
        assertThrows(IllegalStateException.class, () -> service.isInterState("", "21"));
        assertThrows(IllegalStateException.class, () -> service.isInterState("21", null));
    }
}
