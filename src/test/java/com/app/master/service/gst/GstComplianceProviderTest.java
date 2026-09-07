package com.app.master.service.gst;

import com.app.master.service.service.admin.provider.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Government integration providers.
 *
 * The rule under test everywhere here: an unconfigured integration reports
 * EXTERNAL_DEPENDENCY and produces no identifier. Nothing in the application may
 * invent an IRN, an e-way bill number, an ARN or an acknowledgement.
 */
class GstComplianceProviderTest {

    // ── e-Invoice ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An unconfigured IRP reports EXTERNAL_DEPENDENCY and returns no IRN")
    void einvoiceUnconfiguredReturnsExternalDependency() {
        var provider = new UnconfiguredEinvoiceProvider();
        ReflectionTestUtils.setField(provider, "baseUrl", "");
        ReflectionTestUtils.setField(provider, "username", "");

        assertFalse(provider.isConfigured());
        var r = provider.register(1L, "{}");

        assertEquals(EinvoiceProvider.FAILED, r.status());
        assertNull(r.irn());
        assertNull(r.acknowledgementNumber());
        assertTrue(r.error().startsWith("EXTERNAL_DEPENDENCY:"));
        assertFalse(r.isGenerated());
    }

    @Test
    @DisplayName("A provider is only configured when it has both a URL and credentials")
    void einvoiceConfigurationNeedsUrlAndCredentials() {
        var provider = new UnconfiguredEinvoiceProvider();

        ReflectionTestUtils.setField(provider, "baseUrl", "https://irp.example");
        ReflectionTestUtils.setField(provider, "username", "");
        assertFalse(provider.isConfigured(), "a URL alone is not a configured integration");

        ReflectionTestUtils.setField(provider, "username", "veloria");
        assertTrue(provider.isConfigured());
    }

    @Test
    @DisplayName("GENERATED without an IRN is not treated as a registration")
    void einvoiceGeneratedNeedsBothIdentifiers() {
        var missingIrn = new EinvoiceProvider.Result(
                EinvoiceProvider.GENERATED, null, "ACK1", null, null, null, null, null);
        assertFalse(missingIrn.isGenerated(), "an IRN is required");

        var missingAck = new EinvoiceProvider.Result(
                EinvoiceProvider.GENERATED, "IRN1", "  ", null, null, null, null, null);
        assertFalse(missingAck.isGenerated(), "an acknowledgement number is required");

        var complete = new EinvoiceProvider.Result(
                EinvoiceProvider.GENERATED, "IRN1", "ACK1", null, null, null, null, null);
        assertTrue(complete.isGenerated());
    }

    @Test
    @DisplayName("A failed e-invoice response carries no identifiers")
    void einvoiceFailedCarriesNoIdentifiers() {
        var r = EinvoiceProvider.Result.failed("IRP rejected the payload");
        assertEquals(EinvoiceProvider.FAILED, r.status());
        assertNull(r.irn());
        assertFalse(r.isGenerated());
    }

    // ── e-Way bill ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("An unconfigured e-way bill portal reports EXTERNAL_DEPENDENCY and no bill number")
    void ewayUnconfiguredReturnsExternalDependency() {
        var provider = new UnconfiguredEwayBillProvider();
        ReflectionTestUtils.setField(provider, "baseUrl", "");
        ReflectionTestUtils.setField(provider, "username", "");

        assertFalse(provider.isConfigured());
        var r = provider.generate(1L, "{}");

        assertEquals(EwayBillProvider.FAILED, r.status());
        assertNull(r.ewbNumber());
        assertTrue(r.error().startsWith("EXTERNAL_DEPENDENCY:"));
        assertFalse(r.isGenerated());
    }

    @Test
    @DisplayName("GENERATED without a bill number is not treated as generated")
    void ewayGeneratedNeedsBillNumber() {
        assertFalse(new EwayBillProvider.Result(
                EwayBillProvider.GENERATED, "  ", null, null, null, null).isGenerated());
        assertTrue(new EwayBillProvider.Result(
                EwayBillProvider.GENERATED, "EWB123", null, null, null, null).isGenerated());
    }

    // ── GSTIN verification ───────────────────────────────────────────────────

    @Test
    @DisplayName("Local validation reports FORMAT_VALID and never VERIFIED")
    void localValidatorNeverClaimsGovernmentVerification() {
        var r = GstinVerificationProvider.Result.formatValid("21");

        assertEquals("FORMAT_VALID", r.status());
        assertNotEquals("VERIFIED", r.status(),
                "structural validity is not proof the registration exists");
        assertNull(r.legalName(), "a local check cannot know the legal name");
    }

    @Test
    @DisplayName("A failed verification is distinguishable from an invalid GSTIN")
    void failedIsNotTheSameAsInvalid() {
        assertEquals("VERIFICATION_FAILED",
                GstinVerificationProvider.Result.failed("timeout").status());
        assertEquals("INVALID",
                GstinVerificationProvider.Result.invalid("bad checksum").status());
    }

    // ── Filing ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An unconfigured filing provider never returns FILED or an acknowledgement")
    void filingUnconfiguredNeverReturnsFiled() {
        var r = GstrFilingProvider.FilingResult.notConfigured("no GSP configured");

        assertNotEquals("FILED", r.status());
        assertNull(r.acknowledgementNumber());
        assertTrue(r.error().startsWith("EXTERNAL_DEPENDENCY:"));
    }
}
