package com.app.master.service.payment;

import com.app.master.service.core.entity.GstConfigurationEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.GstConfigurationService;
import com.app.master.service.service.payment.CodFeeTaxResolver;
import com.app.master.service.support.AccountingResidue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cash-on-delivery handling charge as a taxed <em>service</em>.
 *
 * <p>What these are really defending is that nothing about the charge's tax is
 * decided in code. Every case below changes only configuration or the tax master
 * and then checks what the resolver produces — so an implementation that reached
 * for a default rate, a product's rate, or 18% would fail here rather than in
 * production.
 *
 * <p>Every expected figure is computed independently in the test from the rate the
 * test itself configured. Nothing asserts an application output against another
 * application output.
 *
 * <p>Real PostgreSQL, because the resolution path runs through
 * {@code gst_tax_rules}' effective-date and priority SQL and through
 * {@code gst_configuration}'s effective-date filtering — neither of which is
 * exercised by mocking them out.
 */
@SpringBootTest(properties = {
        "AWS_ACCESS_KEY=test-placeholder-not-a-credential",
        "AWS_SECRET_KEY=test-placeholder-not-a-credential",
        // One shared pool size across every integration test, on purpose; see the
        // note in FinancialDecisionsPostgresTest.
        "spring.datasource.hikari.maximum-pool-size=40"
})
class CodServiceTaxPostgresTest {

    /** A service code that exists only for these tests. */
    private static final String TEST_SAC = "999799";
    /** A goods HSN with a real, different rate — used to prove it is never reached for. */
    private static final String PRODUCT_HSN = "6211";
    private static final Long ORG = 1L;
    private static final String KARNATAKA = "29";
    private static final String MAHARASHTRA = "27";

    @Autowired private CodFeeTaxResolver resolver;
    @Autowired private GstConfigurationService config;
    @Autowired private JdbcTemplate jdbc;

    private static final LocalDate TODAY = LocalDate.now();

    // ── fixture ──────────────────────────────────────────────────────────────

    @BeforeEach
    void isolateConfiguration() {
        // The seeded rows are production configuration. Park them for the duration
        // and restore them afterwards, so a test can express "nothing configured"
        // without deleting anything.
        jdbc.update("UPDATE gst_configuration SET config_key = 'ZZ_PARKED_' || config_key "
                  + "WHERE config_key LIKE 'COD\\_%'");
    }

    @AfterEach
    void restoreConfiguration() {
        jdbc.update("DELETE FROM gst_configuration WHERE config_key LIKE 'COD\\_%'");
        jdbc.update("UPDATE gst_configuration SET config_key = substring(config_key from 11) "
                  + "WHERE config_key LIKE 'ZZ\\_PARKED\\_%'");
        jdbc.update("DELETE FROM gst_tax_rules WHERE description LIKE 'Automation COD%'");
        AccountingResidue.assertNone(jdbc);
    }

    private void setConfig(String key, String value) {
        jdbc.update("DELETE FROM gst_configuration WHERE config_key = ? AND organization_id = ?", key, ORG);
        jdbc.update("""
                INSERT INTO gst_configuration (organization_id, config_key, config_value, value_type,
                                               effective_from, active, created_at)
                VALUES (?, ?, ?, 'STRING', DATE '2017-07-01', true, now())
                """, ORG, key, value);
    }

    /** A COD service rule, with the dates and activation the test wants. */
    private void seedCodRule(int cgstBp, int sgstBp, int igstBp,
                             LocalDate from, LocalDate to, boolean active) {
        jdbc.update("""
                INSERT INTO gst_tax_rules (uuid, hsn_code, tax_code_type, hsn_match_type, description,
                                           cgst_rate_bp, sgst_rate_bp, igst_rate_bp, cess_rate_bp,
                                           priority, effective_from, effective_to, active, created)
                VALUES (gen_random_uuid(), ?, 'SAC', 'EXACT', 'Automation COD service rule',
                        ?, ?, ?, 0, 900, ?, ?, ?, now())
                """, TEST_SAC, cgstBp, sgstBp, igstBp, from, to, active);
    }

    /** The fully-configured happy state: ₹50, a SAC, a basis, and a live rule. */
    private void configureFully(int cgstBp, int sgstBp, int igstBp, String basis) {
        setConfig(GstConfigurationService.COD_ENABLED, "true");
        setConfig(GstConfigurationService.COD_FEE_PAISE, "5000");
        setConfig(GstConfigurationService.COD_FEE_SAC, TEST_SAC);
        setConfig(GstConfigurationService.COD_FEE_TAX_BASIS, basis);
        seedCodRule(cgstBp, sgstBp, igstBp, LocalDate.of(2017, 7, 1), null, true);
    }

    // ── the charge is configured, not coded ──────────────────────────────────

    @Test
    @DisplayName("the charge is whatever configuration says, not a literal")
    void theChargeComesFromConfiguration() throws Exception {
        configureFully(600, 600, 1200, GstConfigurationService.TAX_EXCLUSIVE);
        setConfig(GstConfigurationService.COD_FEE_PAISE, "7500");   // ₹75, not ₹50

        var tax = resolver.require(KARNATAKA, KARNATAKA, TODAY);

        assertEquals(7500L, tax.feePaise(), "the configured charge, not a hardcoded ₹50");
        // 12% of ₹75 = ₹9, computed here rather than read back from the application.
        assertEquals(900L, tax.totalTaxPaise());
        assertEquals(8400L, tax.totalChargePaise(), "₹75 + ₹9");
    }

    @Test
    @DisplayName("with no charge configured there is nothing to charge")
    void noChargeConfigured() throws Exception {
        configureFully(600, 600, 1200, GstConfigurationService.TAX_EXCLUSIVE);
        setConfig(GstConfigurationService.COD_FEE_PAISE, "0");

        var tax = resolver.require(KARNATAKA, KARNATAKA, TODAY);

        assertTrue(tax.resolved(), "a zero charge is configured, not broken");
        assertEquals(0L, tax.feePaise());
        assertEquals(0L, tax.totalTaxPaise(), "nothing to tax");
    }

    // ── intra-state and inter-state ─────────────────────────────────────────

    @Test
    @DisplayName("an intra-state supply charges CGST and SGST, and no IGST")
    void intraState() throws Exception {
        configureFully(900, 900, 1800, GstConfigurationService.TAX_EXCLUSIVE);

        var tax = resolver.require(KARNATAKA, KARNATAKA, TODAY);

        // 9% of ₹50 each, worked out here: 5000 × 900 / 10000 = 450.
        assertEquals(450L, tax.cgstPaise(), "9% CGST on ₹50");
        assertEquals(450L, tax.sgstPaise(), "9% SGST on ₹50");
        assertEquals(0L, tax.igstPaise(), "an intra-state supply charges no IGST");
        assertEquals(900L, tax.totalTaxPaise());
        assertEquals(1800, tax.rateBp(), "the combined rate is CGST + SGST");
        assertEquals(5900L, tax.totalChargePaise(), "₹50 + ₹9");
    }

    @Test
    @DisplayName("an inter-state supply charges IGST only")
    void interState() throws Exception {
        configureFully(900, 900, 1800, GstConfigurationService.TAX_EXCLUSIVE);

        var tax = resolver.require(MAHARASHTRA, KARNATAKA, TODAY);

        assertEquals(0L, tax.cgstPaise(), "an inter-state supply charges no CGST");
        assertEquals(0L, tax.sgstPaise(), "nor SGST");
        assertEquals(900L, tax.igstPaise(), "18% IGST on ₹50");
        assertEquals(900L, tax.totalTaxPaise());
        assertEquals(1800, tax.rateBp());
    }

    @Test
    @DisplayName("the two supply types produce the same tax, split differently")
    void bothSupplyTypesAgreeOnTheTotal() throws Exception {
        configureFully(250, 250, 500, GstConfigurationService.TAX_EXCLUSIVE);

        var intra = resolver.require(KARNATAKA, KARNATAKA, TODAY);
        var inter = resolver.require(MAHARASHTRA, KARNATAKA, TODAY);

        // 5% of ₹50 = ₹2.50 either way.
        assertEquals(250L, intra.totalTaxPaise());
        assertEquals(250L, inter.totalTaxPaise());
        assertEquals(125L, intra.cgstPaise(), "split in half intra-state");
        assertEquals(125L, intra.sgstPaise());
        assertEquals(250L, inter.igstPaise(), "and whole inter-state");
    }

    // ── tax basis ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("an exclusive charge adds the tax on top of it")
    void exclusiveBasis() throws Exception {
        configureFully(900, 900, 1800, GstConfigurationService.TAX_EXCLUSIVE);

        var tax = resolver.require(KARNATAKA, KARNATAKA, TODAY);

        assertEquals(5000L, tax.taxablePaise(), "the whole charge is the taxable value");
        assertEquals(900L, tax.totalTaxPaise());
        assertEquals(5900L, tax.totalChargePaise(), "the customer pays more than the charge");
        assertFalse(tax.taxInclusive());
    }

    @Test
    @DisplayName("an inclusive charge carves the tax out of it, and the parts sum back")
    void inclusiveBasis() throws Exception {
        configureFully(900, 900, 1800, GstConfigurationService.TAX_INCLUSIVE);

        var tax = resolver.require(KARNATAKA, KARNATAKA, TODAY);

        // 5000 × 10000 / 11800 = 4237.28… → 4237 HALF_UP.
        assertEquals(4237L, tax.taxablePaise(), "the base inside a ₹50 tax-inclusive charge");
        assertTrue(tax.taxInclusive());
        assertEquals(5000L, tax.totalChargePaise(), "the customer pays exactly the charge");
        assertEquals(5000L, tax.taxablePaise() + tax.totalTaxPaise(),
                "and the base plus its tax is the charge, to the paisa");

        // The tax is the remainder — 5000 − 4237 = 763 — not the rate re-applied to
        // the carved base, which would give 762 and lose a paisa.
        assertEquals(763L, tax.totalTaxPaise());
        assertEquals(381L, tax.cgstPaise(), "half the tax");
        assertEquals(382L, tax.sgstPaise(), "and the odd paisa, deterministically");
    }

    @Test
    @DisplayName("an inclusive charge reconciles at every rate, never losing or inventing a paisa")
    void inclusiveBasisReconcilesAtEveryRate() throws Exception {
        for (int halfRateBp : new int[]{0, 250, 600, 900, 1400}) {   // 0%, 5%, 12%, 18%, 28%
            configureFully(halfRateBp, halfRateBp, halfRateBp * 2,
                    GstConfigurationService.TAX_INCLUSIVE);
            for (long fee : new long[]{1, 7, 50, 99, 5000, 12345, 99999}) {
                setConfig(GstConfigurationService.COD_FEE_PAISE, String.valueOf(fee));

                var intra = resolver.require(KARNATAKA, KARNATAKA, TODAY);
                var inter = resolver.require(MAHARASHTRA, KARNATAKA, TODAY);
                String at = " at " + (halfRateBp * 2) + "bp on " + fee + " paise";

                assertEquals(fee, intra.taxablePaise() + intra.totalTaxPaise(),
                        "intra-state must reconcile exactly" + at);
                assertEquals(fee, inter.taxablePaise() + inter.totalTaxPaise(),
                        "inter-state must reconcile exactly" + at);
                assertEquals(intra.totalTaxPaise(), intra.cgstPaise() + intra.sgstPaise(),
                        "the heads must sum to the tax" + at);
                assertEquals(inter.totalTaxPaise(), inter.igstPaise(),
                        "IGST must carry the whole tax" + at);
                assertEquals(fee, intra.totalChargePaise(),
                        "and the customer pays the charge, no more" + at);
            }
            jdbc.update("DELETE FROM gst_tax_rules WHERE description LIKE 'Automation COD%'");
        }
    }

    @Test
    @DisplayName("the two bases bill the customer differently — which is why neither is defaulted")
    void theTwoBasesDiffer() throws Exception {
        configureFully(900, 900, 1800, GstConfigurationService.TAX_EXCLUSIVE);
        long exclusive = resolver.require(KARNATAKA, KARNATAKA, TODAY).totalChargePaise();

        setConfig(GstConfigurationService.COD_FEE_TAX_BASIS, GstConfigurationService.TAX_INCLUSIVE);
        long inclusive = resolver.require(KARNATAKA, KARNATAKA, TODAY).totalChargePaise();

        assertEquals(5900L, exclusive);
        assertEquals(5000L, inclusive);
        assertNotEquals(exclusive, inclusive,
                "the basis is a billing decision, not a presentation one");
    }

    // ── missing configuration is refused, never approximated ────────────────

    @Test
    @DisplayName("with no SAC configured the charge is refused, not taxed at a guess")
    void noSacIsRefused() {
        setConfig(GstConfigurationService.COD_FEE_PAISE, "5000");
        setConfig(GstConfigurationService.COD_FEE_TAX_BASIS, GstConfigurationService.TAX_EXCLUSIVE);
        seedCodRule(900, 900, 1800, LocalDate.of(2017, 7, 1), null, true);

        assertEquals(CodFeeTaxResolver.NO_SAC_CONFIGURED,
                resolver.resolve(KARNATAKA, KARNATAKA, TODAY).resolution());

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> resolver.require(KARNATAKA, KARNATAKA, TODAY));
        assertTrue(e.getMessage().contains("service accounting code"), e.getMessage());
    }

    @Test
    @DisplayName("with no tax basis configured the charge is refused")
    void noBasisIsRefused() {
        setConfig(GstConfigurationService.COD_FEE_PAISE, "5000");
        setConfig(GstConfigurationService.COD_FEE_SAC, TEST_SAC);
        seedCodRule(900, 900, 1800, LocalDate.of(2017, 7, 1), null, true);

        assertEquals(CodFeeTaxResolver.NO_BASIS_CONFIGURED,
                resolver.resolve(KARNATAKA, KARNATAKA, TODAY).resolution());
        assertThrows(VeloriaException.class, () -> resolver.require(KARNATAKA, KARNATAKA, TODAY));
    }

    @Test
    @DisplayName("with a SAC but no rule in the master the charge is refused, not zero-rated")
    void noRuleIsRefusedNotTreatedAsZero() {
        setConfig(GstConfigurationService.COD_FEE_PAISE, "5000");
        setConfig(GstConfigurationService.COD_FEE_SAC, TEST_SAC);
        setConfig(GstConfigurationService.COD_FEE_TAX_BASIS, GstConfigurationService.TAX_EXCLUSIVE);
        // No rule seeded.

        var tax = resolver.resolve(KARNATAKA, KARNATAKA, TODAY);

        assertEquals(CodFeeTaxResolver.NO_RULE, tax.resolution(),
                "an unconfigured rate is not a 0% rate");
        assertNull(tax.rateBp(), "and a null rate is not a zero rate");
        assertFalse(tax.resolved());

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> resolver.require(KARNATAKA, KARNATAKA, TODAY));
        assertTrue(e.getMessage().contains(TEST_SAC), e.getMessage());
    }

    // ── effective dates and activation ──────────────────────────────────────

    @Test
    @DisplayName("an inactive rule is not used")
    void inactiveRuleIsRejected() {
        setConfig(GstConfigurationService.COD_FEE_PAISE, "5000");
        setConfig(GstConfigurationService.COD_FEE_SAC, TEST_SAC);
        setConfig(GstConfigurationService.COD_FEE_TAX_BASIS, GstConfigurationService.TAX_EXCLUSIVE);
        seedCodRule(900, 900, 1800, LocalDate.of(2017, 7, 1), null, false);

        assertEquals(CodFeeTaxResolver.NO_RULE,
                resolver.resolve(KARNATAKA, KARNATAKA, TODAY).resolution(),
                "a withdrawn rule must not price a charge");
    }

    @Test
    @DisplayName("a rule that starts tomorrow is not used today")
    void futureRuleIsNotUsedEarly() {
        setConfig(GstConfigurationService.COD_FEE_PAISE, "5000");
        setConfig(GstConfigurationService.COD_FEE_SAC, TEST_SAC);
        setConfig(GstConfigurationService.COD_FEE_TAX_BASIS, GstConfigurationService.TAX_EXCLUSIVE);
        seedCodRule(900, 900, 1800, TODAY.plusDays(1), null, true);

        assertEquals(CodFeeTaxResolver.NO_RULE,
                resolver.resolve(KARNATAKA, KARNATAKA, TODAY).resolution());
        // …but it does apply once its date arrives.
        assertTrue(resolver.resolve(KARNATAKA, KARNATAKA, TODAY.plusDays(1)).resolved());
    }

    @Test
    @DisplayName("a rule that ended yesterday is not used today")
    void expiredRuleIsNotUsed() {
        setConfig(GstConfigurationService.COD_FEE_PAISE, "5000");
        setConfig(GstConfigurationService.COD_FEE_SAC, TEST_SAC);
        setConfig(GstConfigurationService.COD_FEE_TAX_BASIS, GstConfigurationService.TAX_EXCLUSIVE);
        seedCodRule(900, 900, 1800, LocalDate.of(2017, 7, 1), TODAY.minusDays(1), true);

        assertEquals(CodFeeTaxResolver.NO_RULE,
                resolver.resolve(KARNATAKA, KARNATAKA, TODAY).resolution());
        // …and it still prices a charge dated while it was live.
        assertTrue(resolver.resolve(KARNATAKA, KARNATAKA, TODAY.minusDays(2)).resolved());
    }

    @Test
    @DisplayName("where two rules overlap, the existing precedence picks the higher priority")
    void overlappingRulesUseExistingPrecedence() throws Exception {
        setConfig(GstConfigurationService.COD_ENABLED, "true");
        setConfig(GstConfigurationService.COD_FEE_PAISE, "5000");
        setConfig(GstConfigurationService.COD_FEE_SAC, TEST_SAC);
        setConfig(GstConfigurationService.COD_FEE_TAX_BASIS, GstConfigurationService.TAX_EXCLUSIVE);

        // Both live today; the second carries the higher priority.
        seedCodRule(250, 250, 500, LocalDate.of(2017, 7, 1), null, true);
        jdbc.update("""
                INSERT INTO gst_tax_rules (uuid, hsn_code, tax_code_type, hsn_match_type, description,
                                           cgst_rate_bp, sgst_rate_bp, igst_rate_bp, cess_rate_bp,
                                           priority, effective_from, active, created)
                VALUES (gen_random_uuid(), ?, 'SAC', 'EXACT', 'Automation COD service rule (high priority)',
                        900, 900, 1800, 0, 950, DATE '2017-07-01', true, now())
                """, TEST_SAC);

        var tax = resolver.require(KARNATAKA, KARNATAKA, TODAY);

        // 9% + 9%, from the priority-950 rule — not 2.5% + 2.5% from the other.
        assertEquals(1800, tax.rateBp(), "the higher-priority rule wins");
        assertEquals(900L, tax.totalTaxPaise());
    }

    // ── the charge must never borrow the product's tax ──────────────────────

    @Test
    @DisplayName("the product's HSN is never used to price the charge")
    void productHsnIsNeverUsedForCod() {
        setConfig(GstConfigurationService.COD_FEE_PAISE, "5000");
        setConfig(GstConfigurationService.COD_FEE_TAX_BASIS, GstConfigurationService.TAX_EXCLUSIVE);
        // A SAC is configured that has no rule; the product HSN 6211 DOES have one.
        setConfig(GstConfigurationService.COD_FEE_SAC, TEST_SAC);

        Long productRules = jdbc.queryForObject(
                "SELECT count(*) FROM gst_tax_rules WHERE ? LIKE hsn_code || '%' AND active",
                Long.class, PRODUCT_HSN);
        assertTrue(productRules != null && productRules > 0,
                "the product HSN must have a live rule for this test to mean anything");

        // With a priceable product rule sitting right there, the charge is still
        // refused — which is only possible if nothing falls back to it.
        assertEquals(CodFeeTaxResolver.NO_RULE,
                resolver.resolve(KARNATAKA, KARNATAKA, TODAY).resolution());
    }

    @Test
    @DisplayName("the charge's rate is its own, even when the product's rate differs")
    void codRateIsIndependentOfTheProductRate() throws Exception {
        // The product HSN 6211 is configured at 5% or 12% by the seeded rules; the
        // COD service is configured at 18%. They must not converge.
        configureFully(900, 900, 1800, GstConfigurationService.TAX_EXCLUSIVE);

        var cod = resolver.require(KARNATAKA, KARNATAKA, TODAY);

        Integer productCombined = jdbc.queryForObject("""
                SELECT cgst_rate_bp + sgst_rate_bp FROM gst_tax_rules
                 WHERE ? LIKE hsn_code || '%' AND active
                 ORDER BY priority DESC LIMIT 1
                """, Integer.class, PRODUCT_HSN);

        assertEquals(1800, cod.rateBp(), "the COD rate is the one configured for the service");
        assertNotEquals(productCombined, cod.rateBp(),
                "and it is not the product's rate — they are different supplies");
        assertEquals(TEST_SAC, cod.sacCode(), "priced under the service code, not an HSN");
    }

    // ── identity and configuration surface ──────────────────────────────────

    @Test
    @DisplayName("the charge identifies itself as a service, consistently")
    void serviceIdentity() throws Exception {
        configureFully(900, 900, 1800, GstConfigurationService.TAX_EXCLUSIVE);

        var tax = resolver.require(KARNATAKA, KARNATAKA, TODAY);

        assertEquals("COD_FEE", tax.serviceCode());
        assertEquals("Cash on Delivery Handling Fee", tax.serviceName(),
                "the configured description, falling back to the default when unset");
        assertEquals(TEST_SAC, tax.sacCode());
    }

    @Test
    @DisplayName("COD is off unless configuration switches it on")
    void codIsOffByDefault() {
        // Nothing configured at all — the parked state from @BeforeEach.
        assertFalse(config.codEnabled(ORG), "an unconfigured application does not offer COD");

        setConfig(GstConfigurationService.COD_ENABLED, "true");
        assertTrue(config.codEnabled(ORG));
    }

    @Test
    @DisplayName("the handling charge is not refundable unless configuration says so")
    void refundabilityIsConfigurableAndOffByDefault() {
        assertFalse(config.codFeeRefundable(ORG),
                "silence keeps the standing rule: the charge is not refunded");

        setConfig(GstConfigurationService.COD_FEE_REFUNDABLE, "true");
        assertTrue(config.codFeeRefundable(ORG));
    }

    @Test
    @DisplayName("an unrecognised tax basis is treated as unconfigured, not as a default")
    void garbageBasisIsNotSilentlyAccepted() {
        setConfig(GstConfigurationService.COD_FEE_TAX_BASIS, "SOMETHING_ELSE");

        assertTrue(config.codFeeTaxInclusive(ORG).isEmpty(),
                "an unrecognised value must not resolve to either basis");
    }

    // ── the approved configuration, end to end ──────────────────────────────

    /**
     * The exact figures P0-13 approved, against the shipped rule rather than a
     * test's own.
     *
     * <p>Every number here is worked out in the test: 5000 × 10000 / 11800 = 4237.29
     * → 4237 HALF_UP, and the tax is the ₹50 less that, 763. Nothing is read back
     * from the application and re-asserted against itself.
     */
    @Test
    @DisplayName("the approved configuration bills ₹50 as ₹42.37 taxable plus ₹7.63 GST")
    void theApprovedConfiguration() throws Exception {
        // The production rows, unparked for this test, and the rule the migration
        // seeded — no test rule at all.
        jdbc.update("DELETE FROM gst_configuration WHERE config_key LIKE 'COD\\_%'");
        jdbc.update("UPDATE gst_configuration SET config_key = substring(config_key from 11) "
                  + "WHERE config_key LIKE 'ZZ\\_PARKED\\_%'");

        var intra = resolver.require(KARNATAKA, KARNATAKA, TODAY);

        assertEquals("998599", intra.sacCode(), "the approved service classification");
        assertEquals("Cash on Delivery Handling Fee", intra.serviceName());
        assertEquals(1800, intra.rateBp(), "18%");
        assertTrue(intra.taxInclusive(), "the ₹50 contains the GST");

        assertEquals(5000L, intra.feePaise(),   "₹50.00 charged");
        assertEquals(4237L, intra.taxablePaise(), "₹42.37 taxable");
        assertEquals(763L,  intra.totalTaxPaise(), "₹7.63 GST");
        assertEquals(5000L, intra.totalChargePaise(), "and the customer pays ₹50.00, not ₹59.00");
        assertEquals(5000L, intra.taxablePaise() + intra.totalTaxPaise(), "to the paisa");

        // Intra-state: half each, the odd paisa to SGST, deterministically.
        assertEquals(381L, intra.cgstPaise());
        assertEquals(382L, intra.sgstPaise());
        assertEquals(0L,   intra.igstPaise());

        var inter = resolver.require(MAHARASHTRA, KARNATAKA, TODAY);
        assertEquals(763L, inter.igstPaise(), "inter-state: the whole tax as IGST");
        assertEquals(0L, inter.cgstPaise());
        assertEquals(0L, inter.sgstPaise());
        assertEquals(5000L, inter.totalChargePaise(), "the customer still pays ₹50.00");

        // Park them again so the shared teardown behaves as it does for every other
        // test in this class.
        jdbc.update("UPDATE gst_configuration SET config_key = 'ZZ_PARKED_' || config_key "
                  + "WHERE config_key LIKE 'COD\\_%'");
    }

    // ── place of supply ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a missing place of supply is a validation error, never a 500")
    void missingPlaceOfSupplyIsAValidationError() {
        configureFully(900, 900, 1800, GstConfigurationService.TAX_INCLUSIVE);

        for (String missing : new String[]{null, "", "   "}) {
            var tax = resolver.resolve(missing, KARNATAKA, TODAY);
            assertEquals(CodFeeTaxResolver.NO_PLACE_OF_SUPPLY, tax.resolution(),
                    "place of supply [" + missing + "] must resolve to NO_PLACE_OF_SUPPLY");
            assertFalse(tax.resolved());

            VeloriaException e = assertThrows(VeloriaException.class,
                    () -> resolver.require(missing, KARNATAKA, TODAY));
            // A VeloriaException carries a ResponseCode and becomes a 400; an
            // IllegalStateException escaping the GST engine would become a 500.
            assertEquals(ResponseCode.BAD_REQUEST, e.getErrorCode(),
                    "a business validation problem must not be a server error");
            assertTrue(e.getMessage().contains("place of supply"), e.getMessage());
        }
    }

    @Test
    @DisplayName("a missing seller state is also a validation error, not a 500")
    void missingSellerStateIsAValidationError() {
        configureFully(900, 900, 1800, GstConfigurationService.TAX_INCLUSIVE);

        var tax = resolver.resolve(KARNATAKA, null, TODAY);
        assertEquals(CodFeeTaxResolver.NO_PLACE_OF_SUPPLY, tax.resolution());
        VeloriaException e = assertThrows(VeloriaException.class,
                () -> resolver.require(KARNATAKA, null, TODAY));
        assertEquals(ResponseCode.BAD_REQUEST, e.getErrorCode());
    }

    @Test
    @DisplayName("an unrecognised state code is still treated as a state, and taxed inter-state")
    void unknownStateCodeIsTaxedByComparisonNotByLookup() throws Exception {
        configureFully(900, 900, 1800, GstConfigurationService.TAX_INCLUSIVE);

        // The engine compares codes; it does not validate them against the state
        // master. An unrecognised code that differs from the seller's is therefore
        // inter-state — which is the safe direction, because IGST on an unknown
        // destination is recoverable where a wrong CGST/SGST split is not.
        var tax = resolver.require("99", KARNATAKA, TODAY);

        assertTrue(tax.resolved());
        assertEquals(763L, tax.igstPaise(), "treated as inter-state");
        assertEquals(0L, tax.cgstPaise() + tax.sgstPaise());
    }

    // ── production configuration ────────────────────────────────────────────

    /**
     * What a deployment actually starts from.
     *
     * <p>Until P0-13 this asserted the opposite — that no SAC and no basis shipped —
     * because both were unapproved. They are now approved, so the shipped
     * configuration carries them, and this pins the approved values rather than
     * their absence.
     */
    @Test
    @DisplayName("the shipped configuration is the approved one: ₹50 inclusive under SAC 998599")
    void shippedConfigurationCarriesTheApprovedValues() {
        // Read the parked production rows directly: this is what the migration seeded.
        assertEquals("998599", jdbc.queryForObject(
                "SELECT config_value FROM gst_configuration WHERE config_key = 'ZZ_PARKED_COD_FEE_SAC'",
                String.class), "the approved service classification");
        assertEquals("INCLUSIVE", jdbc.queryForObject(
                "SELECT config_value FROM gst_configuration WHERE config_key = 'ZZ_PARKED_COD_FEE_TAX_BASIS'",
                String.class), "the ₹50 the customer sees contains the GST");
        assertEquals("5000", jdbc.queryForObject(
                "SELECT config_value FROM gst_configuration WHERE config_key = 'ZZ_PARKED_COD_FEE_PAISE'",
                String.class), "the approved ₹50 charge");
        assertEquals("Cash on Delivery Handling Fee", jdbc.queryForObject(
                "SELECT config_value FROM gst_configuration WHERE config_key = 'ZZ_PARKED_COD_FEE_SERVICE_NAME'",
                String.class));
    }

    @Test
    @DisplayName("the shipped COD rule is a SAC rule at 18%, and there is exactly one")
    void shippedCodRuleIsTheApprovedOne() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM gst_tax_rules WHERE hsn_code = '998599' AND tax_code_type = 'SAC'",
                Long.class);
        assertEquals(1L, count, "exactly one rule for the approved SAC");

        Map<String, Object> rule = jdbc.queryForMap("""
                SELECT cgst_rate_bp, sgst_rate_bp, igst_rate_bp, hsn_match_type, active, effective_to
                  FROM gst_tax_rules WHERE hsn_code = '998599' AND tax_code_type = 'SAC'
                """);
        assertEquals(900, rule.get("cgst_rate_bp"), "9% CGST");
        assertEquals(900, rule.get("sgst_rate_bp"), "9% SGST");
        assertEquals(1800, rule.get("igst_rate_bp"), "18% IGST");
        assertEquals("EXACT", rule.get("hsn_match_type"),
                "a SAC must match exactly — a prefix rule would catch unrelated codes");
        assertEquals(true, rule.get("active"));
        assertNull(rule.get("effective_to"), "open-ended until a rate change supersedes it");
    }

    @Test
    @DisplayName("no rule classifies a service code as goods, or a goods code as a service")
    void codeTypesAreNotMixedUp() {
        Long serviceRulesTypedAsGoods = jdbc.queryForObject(
                "SELECT count(*) FROM gst_tax_rules WHERE hsn_code ~ '^99' AND tax_code_type = 'HSN'",
                Long.class);
        assertEquals(0L, serviceRulesTypedAsGoods,
                "a 99xx code is a service accounting code, not an HSN");

        Long goodsRulesTypedAsService = jdbc.queryForObject(
                "SELECT count(*) FROM gst_tax_rules WHERE hsn_code !~ '^99' AND tax_code_type = 'SAC'",
                Long.class);
        assertEquals(0L, goodsRulesTypedAsService, "and the reverse");
    }

    @Test
    @DisplayName("configuration honours its own effective dates")
    void configurationEffectiveDatesAreHonoured() {
        jdbc.update("""
                INSERT INTO gst_configuration (organization_id, config_key, config_value, value_type,
                                               effective_from, effective_to, active, created_at)
                VALUES (?, ?, '9999', 'LONG', DATE '2017-07-01', DATE '2017-07-02', true, now())
                """, ORG, GstConfigurationService.COD_FEE_PAISE);

        assertTrue(config.codFeePaise(ORG).isEmpty(),
                "a value whose effective window has passed must not be used");
    }
}
