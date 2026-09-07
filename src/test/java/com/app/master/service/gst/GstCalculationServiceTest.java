package com.app.master.service.gst;

import com.app.master.service.core.entity.BusinessDetailsEntity;
import com.app.master.service.core.entity.GstTaxRuleEntity;
import com.app.master.service.repository.admin.BusinessDetailsRepository;
import com.app.master.service.repository.admin.GstRegistrationRepository;
import com.app.master.service.repository.admin.GstStateMasterRepository;
import com.app.master.service.repository.admin.OrganizationRepository;
import com.app.master.service.repository.admin.GstTaxRuleRepository;
import com.app.master.service.service.admin.GstCalculationService;
import com.app.master.service.service.admin.GstIdentityService;
import com.app.master.service.service.admin.GstRoundingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Sales scenarios 1-12 from spec section 50, plus the CGST/SGST/IGST
 * invariants from section 51.
 *
 * Seller is Odisha (21), matching the business GSTIN.
 */
class GstCalculationServiceTest {

    private static final String ODISHA = "21";
    private static final String MAHARASHTRA = "27";
    private static final String KARNATAKA = "29";

    private GstTaxRuleRepository ruleRepo;
    private GstCalculationService service;

    @BeforeEach
    void setUp() {
        ruleRepo = mock(GstTaxRuleRepository.class);

        BusinessDetailsRepository bizRepo = mock(BusinessDetailsRepository.class);
        BusinessDetailsEntity biz = new BusinessDetailsEntity();
        biz.setGstNumber("21AABCU9603R1ZX");
        biz.setSellerStateCode("21");
        when(bizRepo.findFirstByArchiveFalseOrderByIdAsc()).thenReturn(Optional.of(biz));

        GstIdentityService identity = new GstIdentityService(
                bizRepo,
                mock(GstStateMasterRepository.class),
                mock(GstRegistrationRepository.class),
                mock(OrganizationRepository.class));
        service = new GstCalculationService(ruleRepo, new GstRoundingService(), identity);
    }

    private void ruleFor(String hsn, int cgstBp, int sgstBp, int igstBp) {
        GstTaxRuleEntity rule = GstTaxRuleEntity.builder()
                .hsnCode(hsn).hsnMatchType("PREFIX")
                .cgstRateBp(cgstBp).sgstRateBp(sgstBp).igstRateBp(igstBp)
                .effectiveFrom(LocalDate.of(2017, 7, 1))
                .active(true).priority(1)
                .build();
        when(ruleRepo.findMatchingRules(eq(hsn), anyLong(), any())).thenReturn(List.of(rule));
    }

    @Test
    @DisplayName("Scenario 1: Odisha to Odisha is CGST + SGST, IGST zero")
    void intraStateSplitsCgstSgst() {
        ruleFor("6204", 250, 250, 500);

        var r = service.calculate("6204", 100000L, 1, ODISHA, ODISHA, LocalDate.now());

        assertFalse(r.interState());
        assertEquals(2500L, r.cgstAmount());
        assertEquals(2500L, r.sgstAmount());
        assertEquals(0L, r.igstAmount(), "Intra-state supply must have zero IGST");
        assertEquals(5000L, r.totalTax());
    }

    @Test
    @DisplayName("Scenario 2/3: Odisha to Maharashtra or Karnataka is IGST only")
    void interStateChargesIgstOnly() {
        ruleFor("6204", 250, 250, 500);

        for (String buyer : List.of(MAHARASHTRA, KARNATAKA)) {
            var r = service.calculate("6204", 100000L, 1, buyer, ODISHA, LocalDate.now());
            assertTrue(r.interState());
            assertEquals(0L, r.cgstAmount(), "Inter-state supply must have zero CGST");
            assertEquals(0L, r.sgstAmount(), "Inter-state supply must have zero SGST");
            assertEquals(5000L, r.igstAmount());
            assertEquals(5000L, r.totalTax());
        }
    }

    @Test
    @DisplayName("Invariant: CGST + SGST + IGST always equals total tax")
    void taxHeadsSumToTotal() {
        ruleFor("6204", 900, 900, 1800);

        var intra = service.calculate("6204", 123457L, 3, ODISHA, ODISHA, LocalDate.now());
        assertEquals(intra.cgstAmount() + intra.sgstAmount() + intra.igstAmount(), intra.totalTax());

        var inter = service.calculate("6204", 123457L, 3, MAHARASHTRA, ODISHA, LocalDate.now());
        assertEquals(inter.cgstAmount() + inter.sgstAmount() + inter.igstAmount(), inter.totalTax());
    }

    @Test
    @DisplayName("Tax is charged on quantity times unit price, not one unit")
    void quantityIsHonoured() {
        ruleFor("6204", 250, 250, 500);

        var one   = service.calculate("6204", 100000L, 1, ODISHA, ODISHA, LocalDate.now());
        var three = service.calculate("6204", 100000L, 3, ODISHA, ODISHA, LocalDate.now());

        assertEquals(100000L, one.taxableValuePaise());
        assertEquals(300000L, three.taxableValuePaise());
        assertEquals(3 * one.cgstAmount(), three.cgstAmount());
        assertEquals(3 * one.totalTax(), three.totalTax());
    }

    @Test
    @DisplayName("Scenario 8: different HSN codes attract their own rates")
    void differentRatesPerHsn() {
        ruleFor("6204", 250, 250, 500);   // 5%
        ruleFor("4202", 900, 900, 1800);  // 18%

        var apparel = service.calculate("6204", 100000L, 1, ODISHA, ODISHA, LocalDate.now());
        var bag     = service.calculate("4202", 100000L, 1, ODISHA, ODISHA, LocalDate.now());

        assertEquals(5000L, apparel.totalTax());
        assertEquals(18000L, bag.totalTax());
    }

    @Test
    @DisplayName("A product with no HSN is unresolved, not silently 0%")
    void missingHsnIsUnresolved() {
        var r = service.calculate(null, 100000L, 1, ODISHA, ODISHA, LocalDate.now());

        assertEquals("NO_HSN", r.resolution());
        assertTrue(r.unresolved(), "Missing HSN must be distinguishable from a genuine 0% rate");
        assertEquals(0L, r.totalTax());
        assertEquals(100000L, r.taxableValuePaise(), "Taxable value is still recorded");
    }

    @Test
    @DisplayName("An HSN with no matching rule is unresolved, not silently 0%")
    void noMatchingRuleIsUnresolved() {
        when(ruleRepo.findMatchingRules(anyString(), anyLong(), any())).thenReturn(List.of());

        var r = service.calculate("9999", 100000L, 1, ODISHA, ODISHA, LocalDate.now());

        assertEquals("NO_RULE", r.resolution());
        assertTrue(r.unresolved());
        assertEquals(0L, r.totalTax());
    }

    @Test
    @DisplayName("Historical rate lookup uses the transaction date, not today")
    void usesTransactionDate() {
        ruleFor("6204", 250, 250, 500);
        LocalDate augustSale = LocalDate.of(2026, 8, 15);

        service.calculate("6204", 100000L, 1, ODISHA, ODISHA, augustSale);

        verify(ruleRepo).findMatchingRules("6204", 100000L, augustSale);
    }

    @Test
    @DisplayName("An unresolved place of supply propagates rather than defaulting")
    void unresolvedPlaceOfSupplyThrows() {
        ruleFor("6204", 250, 250, 500);
        assertThrows(IllegalStateException.class,
                () -> service.calculate("6204", 100000L, 1, null, ODISHA, LocalDate.now()));
    }
}
