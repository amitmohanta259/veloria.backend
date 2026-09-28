package com.app.master.service.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Configures the cash-on-delivery charge for a test, the way an administrator
 * would.
 *
 * <p>Since P0-14 the charge's service code, tax basis and rate are configuration
 * and tax-master data rather than code, so a test that uses COD has to set them
 * up — and one that wants to prove COD is refused has to leave them alone.
 * Reaching into the resolver's fields is deliberately not offered: the whole point
 * of the charge being configured is that the configuration is the only way in.
 *
 * <p>{@link #restore} puts the shipped configuration back — since P0-13 that means
 * ₹50 inclusive under SAC 998599 — so a test that set its own service code or rate
 * cannot leave it behind for the next one.
 */
public final class CodTestConfig {

    /**
     * A service code that exists only in tests.
     *
     * <p>Deliberately not the configured 998599: a test that seeds its own rate must
     * not collide with the production rule, and using a different code proves the
     * resolver follows configuration rather than a built-in code.
     */
    public static final String TEST_SAC = "999799";

    private CodTestConfig() {}

    /**
     * Enables COD at ₹50 inclusive, 9% + 9% intra-state and 18% inter-state.
     *
     * <p>Inclusive because that is the configured production basis: a ₹50 charge the
     * customer sees, with the GST inside it.
     */
    public static void configure(JdbcTemplate jdbc) {
        configure(jdbc, 5000L, 900, 900, 1800, "INCLUSIVE");
    }

    /**
     * Enables COD with the given charge, rates and basis.
     *
     * @param basis EXCLUSIVE — tax added to the charge; INCLUSIVE — tax inside it
     */
    public static void configure(JdbcTemplate jdbc, long feePaise,
                                 int cgstBp, int sgstBp, int igstBp, String basis) {
        jdbc.update("DELETE FROM gst_tax_rules WHERE hsn_code = ? AND description LIKE 'Automation COD%'",
                TEST_SAC);
        jdbc.update("""
                INSERT INTO gst_tax_rules (uuid, hsn_code, tax_code_type, hsn_match_type, description,
                                           cgst_rate_bp, sgst_rate_bp, igst_rate_bp, cess_rate_bp,
                                           priority, effective_from, active, created)
                VALUES (gen_random_uuid(), ?, 'SAC', 'EXACT', 'Automation COD handling charge',
                        ?, ?, ?, 0, 900, DATE '2017-07-01', true, now())
                """, TEST_SAC, cgstBp, sgstBp, igstBp);

        set(jdbc, "COD_ENABLED", "true");
        set(jdbc, "COD_FEE_PAISE", String.valueOf(feePaise));
        set(jdbc, "COD_FEE_SAC", TEST_SAC);
        set(jdbc, "COD_FEE_TAX_BASIS", basis);
    }

    /** Whether the charge comes back on a refund. */
    public static void setRefundable(JdbcTemplate jdbc, boolean refundable) {
        set(jdbc, "COD_FEE_REFUNDABLE", String.valueOf(refundable));
    }

    /**
     * Puts the shipped configuration back.
     *
     * <p>Since P0-13 that means <em>configured</em>: ₹50 inclusive under SAC 998599,
     * priced by the rule the migration seeds. What it must not leave behind is a
     * test's own service code or rate.
     */
    public static void restore(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM gst_tax_rules WHERE description LIKE 'Automation COD%'");
        jdbc.update("UPDATE gst_configuration SET config_value = '998599' "
                  + "WHERE config_key = 'COD_FEE_SAC' AND organization_id = 1");
        jdbc.update("UPDATE gst_configuration SET config_value = 'INCLUSIVE' "
                  + "WHERE config_key = 'COD_FEE_TAX_BASIS' AND organization_id = 1");
        jdbc.update("UPDATE gst_configuration SET config_value = '5000' "
                  + "WHERE config_key = 'COD_FEE_PAISE' AND organization_id = 1");
        jdbc.update("UPDATE gst_configuration SET config_value = 'false' "
                  + "WHERE config_key = 'COD_FEE_REFUNDABLE' AND organization_id = 1");
        jdbc.update("UPDATE gst_configuration SET config_value = 'Cash on Delivery Handling Fee' "
                  + "WHERE config_key = 'COD_FEE_SERVICE_NAME' AND organization_id = 1");
    }

    private static void set(JdbcTemplate jdbc, String key, String value) {
        int updated = jdbc.update("UPDATE gst_configuration SET config_value = ? "
                               + "WHERE config_key = ? AND organization_id = 1", value, key);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO gst_configuration (organization_id, config_key, config_value,
                                                   value_type, effective_from, active, created_at)
                    VALUES (1, ?, ?, 'STRING', DATE '2017-07-01', true, now())
                    """, key, value);
        }
    }
}
