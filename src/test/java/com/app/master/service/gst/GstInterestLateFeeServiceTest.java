package com.app.master.service.gst;

import com.app.master.service.core.entity.GstInterestRuleEntity;
import com.app.master.service.core.entity.GstLateFeeRuleEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.security.GstSecurityContext;
import com.app.master.service.repository.admin.GstInterestRuleRepository;
import com.app.master.service.repository.admin.GstLateFeeRuleRepository;
import com.app.master.service.service.admin.GstAuditService;
import com.app.master.service.service.admin.GstIdentityService;
import com.app.master.service.service.admin.GstInterestLateFeeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Statutory interest and late fee.
 *
 * The rule under test: an unconfigured rate produces REQUIRES_CONFIGURATION,
 * never a plausible-looking number.
 */
class GstInterestLateFeeServiceTest {

    private GstInterestRuleRepository interestRepo;
    private GstLateFeeRuleRepository lateFeeRepo;
    private GstInterestLateFeeService service;

    @BeforeEach
    void setUp() {
        interestRepo = mock(GstInterestRuleRepository.class);
        lateFeeRepo = mock(GstLateFeeRuleRepository.class);
        GstIdentityService identity = mock(GstIdentityService.class);
        GstAuditService audit = mock(GstAuditService.class);
        GstSecurityContext ctx = mock(GstSecurityContext.class);

        when(ctx.current()).thenReturn(Optional.empty());
        when(ctx.actor()).thenReturn("tester");
        when(identity.defaultOrganizationId()).thenReturn(1L);
        when(interestRepo.findByOrganizationIdAndRuleTypeAndActiveTrueOrderByEffectiveFromDesc(any(), anyString()))
                .thenReturn(List.of());
        when(lateFeeRepo.findByOrganizationIdAndReturnTypeAndActiveTrueOrderByEffectiveFromDesc(any(), anyString()))
                .thenReturn(List.of());
        when(interestRepo.findByOrganizationIdAndActiveTrueOrderByEffectiveFromDesc(any()))
                .thenReturn(List.of());
        when(lateFeeRepo.findByOrganizationIdAndActiveTrueOrderByEffectiveFromDesc(any()))
                .thenReturn(List.of());
        when(interestRepo.save(any())).thenAnswer(i -> {
            GstInterestRuleEntity e = i.getArgument(0);
            if (e.getId() == null) e.setId(1L);
            return e;
        });
        when(lateFeeRepo.save(any())).thenAnswer(i -> {
            GstLateFeeRuleEntity e = i.getArgument(0);
            if (e.getId() == null) e.setId(1L);
            return e;
        });

        service = new GstInterestLateFeeService(interestRepo, lateFeeRepo, identity, audit, ctx);
    }

    private GstInterestRuleEntity rule(int rateBp, LocalDate from, LocalDate to) {
        return GstInterestRuleEntity.builder()
                .id(1L).organizationId(1L).ruleType("LATE_PAYMENT").rateBp(rateBp)
                .effectiveFrom(from).effectiveTo(to).sourceReference("Notification 13/2017")
                .active(true).build();
    }

    // ── Unconfigured ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("With no interest rule configured, no rate is assumed")
    void interestWithoutRuleRequiresConfiguration() {
        var c = service.interest("LATE_PAYMENT", 100000L, 30, LocalDate.of(2026, 9, 1));

        assertEquals(GstInterestLateFeeService.REQUIRES_CONFIGURATION, c.status());
        assertNull(c.amountPaise(), "a missing rate is not the same as zero liability");
        assertTrue(c.detail().contains("does not assume a rate"));
    }

    @Test
    @DisplayName("With no late-fee rule configured, no fee is assumed")
    void lateFeeWithoutRuleRequiresConfiguration() {
        var c = service.lateFee("GSTR3B", 5, LocalDate.of(2026, 9, 1));

        assertEquals(GstInterestLateFeeService.REQUIRES_CONFIGURATION, c.status());
        assertNull(c.amountPaise());
    }

    @Test
    @DisplayName("Configuration reports which rates are missing")
    void configurationReportsStatus() {
        var cfg = service.configuration();
        assertEquals(GstInterestLateFeeService.REQUIRES_CONFIGURATION, cfg.get("interestStatus"));
        assertEquals(GstInterestLateFeeService.REQUIRES_CONFIGURATION, cfg.get("lateFeeStatus"));
    }

    // ── Configured ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Interest is simple daily on the configured rate")
    void interestUsesConfiguredRate() {
        when(interestRepo.findByOrganizationIdAndRuleTypeAndActiveTrueOrderByEffectiveFromDesc(any(), anyString()))
                .thenReturn(List.of(rule(1800, LocalDate.of(2026, 1, 1), null)));

        // ₹1,000 (100000 paise) at 18% a year for 365 days = ₹180 = 18000 paise
        var c = service.interest("LATE_PAYMENT", 100000L, 365, LocalDate.of(2026, 9, 1));

        assertEquals(GstInterestLateFeeService.COMPUTED, c.status());
        assertEquals(18000L, c.amountPaise());
        assertEquals(1800, c.rateBp());
        assertEquals("Notification 13/2017", c.ruleSource(), "the source must travel with the figure");
    }

    @Test
    @DisplayName("The rate in force on the date is used, not the newest one")
    void interestUsesRateInForceOnThatDate() {
        when(interestRepo.findByOrganizationIdAndRuleTypeAndActiveTrueOrderByEffectiveFromDesc(any(), anyString()))
                .thenReturn(List.of(
                        rule(2400, LocalDate.of(2026, 6, 1), null),
                        rule(1800, LocalDate.of(2020, 1, 1), LocalDate.of(2026, 5, 31))));

        var older = service.interest("LATE_PAYMENT", 100000L, 365, LocalDate.of(2026, 3, 1));
        assertEquals(1800, older.rateBp(), "a March 2026 liability uses the rate then in force");

        var newer = service.interest("LATE_PAYMENT", 100000L, 365, LocalDate.of(2026, 9, 1));
        assertEquals(2400, newer.rateBp());
    }

    @Test
    @DisplayName("Late fee is per day and respects the statutory cap")
    void lateFeeIsCapped() {
        when(lateFeeRepo.findByOrganizationIdAndReturnTypeAndActiveTrueOrderByEffectiveFromDesc(any(), anyString()))
                .thenReturn(List.of(GstLateFeeRuleEntity.builder()
                        .id(1L).organizationId(1L).returnType("GSTR3B")
                        .perDayPaise(5000L).maxPaise(500000L)
                        .effectiveFrom(LocalDate.of(2020, 1, 1))
                        .sourceReference("Section 47").active(true).build()));

        var under = service.lateFee("GSTR3B", 10, LocalDate.of(2026, 9, 1));
        assertEquals(50000L, under.amountPaise(), "10 days at 5000 paise");

        var over = service.lateFee("GSTR3B", 1000, LocalDate.of(2026, 9, 1));
        assertEquals(500000L, over.amountPaise(), "capped");
        assertTrue(over.detail().contains("capped"));
    }

    @Test
    @DisplayName("Nothing outstanding produces no interest, and that is a real zero")
    void nothingOutstandingIsZero() {
        var c = service.interest("LATE_PAYMENT", 0L, 30, LocalDate.of(2026, 9, 1));
        assertEquals(GstInterestLateFeeService.COMPUTED, c.status());
        assertEquals(0L, c.amountPaise());
    }

    @Test
    @DisplayName("Filing on time produces no late fee")
    void onTimeIsZero() {
        var c = service.lateFee("GSTR3B", 0, LocalDate.of(2026, 9, 1));
        assertEquals(GstInterestLateFeeService.COMPUTED, c.status());
        assertEquals(0L, c.amountPaise());
    }

    // ── Adding rules ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("A rate without a source reference is refused — it could not be audited")
    void rateWithoutSourceIsRefused() {
        VeloriaException e = assertThrows(VeloriaException.class,
                () -> service.addInterestRule("LATE_PAYMENT", 1800,
                        LocalDate.of(2026, 1, 1), null, "  ", "no source"));
        assertTrue(e.getMessage().contains("source reference is required"));
    }

    @Test
    @DisplayName("A late-fee rule without a source reference is refused")
    void lateFeeWithoutSourceIsRefused() {
        assertThrows(VeloriaException.class,
                () -> service.addLateFeeRule("GSTR3B", 5000L, null,
                        LocalDate.of(2026, 1, 1), null, null, "no source"));
    }

    @Test
    @DisplayName("A rule with a source is accepted and records where the rate came from")
    void ruleWithSourceIsAccepted() throws Exception {
        GstInterestRuleEntity saved = service.addInterestRule("LATE_PAYMENT", 1800,
                LocalDate.of(2026, 1, 1), null, "Notification 13/2017", "Late payment of tax");

        assertEquals(1800, saved.getRateBp());
        assertEquals("Notification 13/2017", saved.getSourceReference());
        assertTrue(saved.getActive());
    }

    @Test
    @DisplayName("A zero or negative rate is refused")
    void nonPositiveRateIsRefused() {
        assertThrows(VeloriaException.class,
                () -> service.addInterestRule("LATE_PAYMENT", 0,
                        LocalDate.of(2026, 1, 1), null, "Notification 13/2017", null));
    }

    @Test
    @DisplayName("A rule with no effective-from date is refused")
    void missingEffectiveFromIsRefused() {
        assertThrows(VeloriaException.class,
                () -> service.addInterestRule("LATE_PAYMENT", 1800,
                        null, null, "Notification 13/2017", null));
    }
}
