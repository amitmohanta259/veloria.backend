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

    public List<GstConfigurationEntity> forOrganization(Long organizationId) {
        return configRepo.findByOrganizationIdAndActiveTrueOrderByConfigKeyAsc(organizationId);
    }

    public GstConfigurationEntity save(GstConfigurationEntity config) {
        return configRepo.save(config);
    }
}
