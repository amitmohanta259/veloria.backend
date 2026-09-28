package com.app.master.service.service.admin;

import com.app.master.service.core.entity.GstConfigurationEntity;
import com.app.master.service.repository.admin.GstConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Configurable GST behaviour (spec phases 12, 13, 26 and 63).
 *
 * Anything that is a business or statutory decision rather than arithmetic
 * lives in {@code gst_configuration}, so it can change without a code release
 * and carries a source reference for whoever has to justify it later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstConfigurationService {

    /** How shipping is taxed. */
    public static final String SHIPPING_TAX_TREATMENT = "SHIPPING_TAX_TREATMENT";
    public static final String TAXABLE_AT_LINE_RATE   = "TAXABLE_AT_LINE_RATE";
    public static final String EXEMPT                 = "EXEMPT";

    /** e-invoice and e-way bill applicability inputs. */
    public static final String EINVOICE_TURNOVER_THRESHOLD_PAISE = "EINVOICE_TURNOVER_THRESHOLD_PAISE";
    public static final String EINVOICE_ENABLED                  = "EINVOICE_ENABLED";
    public static final String EWAYBILL_VALUE_THRESHOLD_PAISE    = "EWAYBILL_VALUE_THRESHOLD_PAISE";
    public static final String EWAYBILL_ENABLED                  = "EWAYBILL_ENABLED";

    // ── Cash on delivery ─────────────────────────────────────────────────────
    //
    // The COD handling charge is a supply of a service, priced and taxed
    // separately from the goods. Everything about it that a business or a tax
    // adviser decides lives here rather than in code: the amount, the service
    // code its rate is looked up by, whether the amount includes the tax, and
    // whether it comes back on a refund.
    //
    // Two of these are deliberately unseeded. COD_FEE_SAC and COD_FEE_TAX_BASIS
    // are unapproved tax decisions, and an unset value is the honest
    // representation of that — not a default that quietly becomes policy.

    /** Whether cash on delivery is offered at all. */
    public static final String COD_ENABLED = "COD_ENABLED";

    /** The handling charge, in paise. Approved at ₹50. */
    public static final String COD_FEE_PAISE = "COD_FEE_PAISE";

    /**
     * The service code the charge's GST rate is looked up by, in
     * {@code gst_tax_rules}.
     *
     * <p>No value is seeded. Which SAC a handling charge is supplied under is a
     * tax determination, and there is no rate in the master to find without it.
     */
    public static final String COD_FEE_SAC = "COD_FEE_SAC";

    /**
     * Whether the charge is the taxable value or already includes the tax.
     *
     * <p>{@link #TAX_EXCLUSIVE} — the customer pays the charge plus tax on it.
     * {@link #TAX_INCLUSIVE} — the customer pays the charge, and the tax is
     * carved out of it.
     *
     * <p>No value is seeded, and there is no fallback. The two give the customer
     * a different bill, so guessing is not a rounding difference — it is
     * over- or under-charging.
     */
    public static final String COD_FEE_TAX_BASIS = "COD_FEE_TAX_BASIS";
    public static final String TAX_EXCLUSIVE     = "EXCLUSIVE";
    public static final String TAX_INCLUSIVE     = "INCLUSIVE";

    /** Whether the charge is returned when an order is refunded. */
    public static final String COD_FEE_REFUNDABLE = "COD_FEE_REFUNDABLE";

    /**
     * How the charge is described to the customer and on the invoice line.
     *
     * <p>A label. It carries no tax meaning — the GST classification comes from
     * {@link #COD_FEE_SAC} alone, and no accounting description determines it.
     */
    public static final String COD_FEE_SERVICE_NAME = "COD_FEE_SERVICE_NAME";

    private final GstConfigurationRepository configRepo;

    /** The effective value for a key today, preferring an org-specific row. */
    public Optional<String> value(Long organizationId, String key) {
        LocalDate today = LocalDate.now();
        // An organization-specific row wins over a global one; JPQL cannot express
        // NULLS LAST, so the preference is applied here.
        return configRepo.findEffective(organizationId, key).stream()
                .filter(c -> c.getEffectiveFrom() == null || !c.getEffectiveFrom().isAfter(today))
                .filter(c -> c.getEffectiveTo() == null || !c.getEffectiveTo().isBefore(today))
                .filter(c -> c.getConfigValue() != null && !c.getConfigValue().isBlank())
                .min(java.util.Comparator.comparingInt(c -> c.getOrganizationId() != null ? 0 : 1))
                .map(GstConfigurationEntity::getConfigValue);
    }

    public String value(Long organizationId, String key, String fallback) {
        return value(organizationId, key).orElse(fallback);
    }

    public Optional<Long> longValue(Long organizationId, String key) {
        return value(organizationId, key).flatMap(v -> {
            try { return Optional.of(Long.parseLong(v.trim())); }
            catch (NumberFormatException e) {
                log.warn("GST configuration {} is not a number: {}", key, v);
                return Optional.empty();
            }
        });
    }

    public boolean booleanValue(Long organizationId, String key, boolean fallback) {
        return value(organizationId, key).map(v -> "true".equalsIgnoreCase(v.trim())).orElse(fallback);
    }

    /**
     * Whether shipping forms part of the taxable value.
     *
     * Defaults to taxable at the line rate, which is the common treatment for a
     * composite supply where shipping is incidental to the goods. The correct
     * treatment is a business and tax decision — it is configuration, not a
     * conclusion this application reaches on its own.
     */
    public boolean shippingIsTaxable(Long organizationId) {
        return !EXEMPT.equalsIgnoreCase(
                value(organizationId, SHIPPING_TAX_TREATMENT, TAXABLE_AT_LINE_RATE));
    }

    // ── Cash on delivery ─────────────────────────────────────────────────────

    /** Whether cash on delivery is offered. Off unless configured on. */
    public boolean codEnabled(Long organizationId) {
        return booleanValue(organizationId, COD_ENABLED, false);
    }

    /** The approved handling charge, or empty when none is configured. */
    public Optional<Long> codFeePaise(Long organizationId) {
        return longValue(organizationId, COD_FEE_PAISE).filter(v -> v >= 0);
    }

    /** How the charge is described, falling back to the supplied default. */
    public String codFeeServiceName(Long organizationId, String fallback) {
        return value(organizationId, COD_FEE_SERVICE_NAME, fallback);
    }

    /** The service code the charge's rate is looked up by, or empty. */
    public Optional<String> codFeeSac(Long organizationId) {
        return value(organizationId, COD_FEE_SAC).map(String::trim).filter(s -> !s.isEmpty());
    }

    /**
     * Whether the charge includes its tax.
     *
     * <p>Empty when unconfigured, and deliberately <em>not</em> defaulted. A
     * caller that needs to know must treat "not decided" as a reason to stop, not
     * as a reason to pick one.
     */
    public Optional<Boolean> codFeeTaxInclusive(Long organizationId) {
        return value(organizationId, COD_FEE_TAX_BASIS).map(String::trim)
                .filter(v -> TAX_INCLUSIVE.equalsIgnoreCase(v) || TAX_EXCLUSIVE.equalsIgnoreCase(v))
                .map(TAX_INCLUSIVE::equalsIgnoreCase);
    }

    /**
     * Whether the charge comes back on a refund.
     *
     * <p>Defaults to <b>not</b> refundable, which is the treatment the schema has
     * expressed since the refund table was created: it has no column for the
     * charge. Configuration can override it; silence keeps the existing rule
     * rather than inventing a new one.
     */
    public boolean codFeeRefundable(Long organizationId) {
        return booleanValue(organizationId, COD_FEE_REFUNDABLE, false);
    }

    public List<GstConfigurationEntity> forOrganization(Long organizationId) {
        return configRepo.findByOrganizationIdAndActiveTrueOrderByConfigKeyAsc(organizationId);
    }

    public GstConfigurationEntity save(GstConfigurationEntity config) {
        return configRepo.save(config);
    }
}
