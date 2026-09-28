package com.app.master.service.service.payment;

import com.app.master.service.core.entity.CustomerOrderEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.GstCalculationService;
import com.app.master.service.service.admin.GstConfigurationService;
import com.app.master.service.service.admin.GstRoundingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * The cash-on-delivery handling charge, and the tax on it.
 *
 * <p>The charge is a supply of a <b>service</b>, not of goods, and it is taxed as
 * one. Nothing here reads a product's HSN, a product's rate, or a product's tax
 * snapshot — a garment's rate has no bearing on what handling a parcel is taxed
 * at, and using one for the other would be a misdeclaration rather than an
 * approximation.
 *
 * <p><b>Everything variable comes from configuration.</b> The amount, the service
 * code its rate is looked up by, and whether that amount includes the tax are all
 * decisions somebody has to make and be able to justify, so they live in {@code
 * gst_configuration} where they carry an effective date and a source reference.
 * The rate itself comes from {@code gst_tax_rules} through the same engine that
 * prices every product — which already honours active/inactive, effective dates
 * and rule precedence, so none of that is re-implemented here.
 *
 * <p><b>Incomplete configuration is refused, never approximated.</b> There is no
 * fallback rate, no default service code, no assumed tax basis and no treating a
 * missing rule as 0%. Each of those would be this application deciding a tax
 * question on its own; {@link #require} throws instead, and says what is missing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodFeeTaxResolver {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Single-tenant today; the same literal every other service uses. */
    private static final Long ORGANIZATION_ID = 1L;

    /** The service this charge is. Fixed identity, not configuration. */
    public static final String SERVICE_CODE = "COD_FEE";
    /**
     * The default description, used when none is configured.
     *
     * <p>A label, not a tax determination — {@code COD_FEE_SERVICE_NAME} overrides
     * it, and neither it nor the accounting label "Other Income" has any bearing on
     * the GST classification, which comes from the SAC alone.
     */
    public static final String SERVICE_NAME = "Cash on Delivery Handling Fee";

    /** No service code is configured, so no rate can be looked up. */
    public static final String NO_SAC_CONFIGURED = "NO_SAC_CONFIGURED";
    /** No tax basis is configured, so the charge cannot be split. */
    public static final String NO_BASIS_CONFIGURED = "NO_BASIS_CONFIGURED";
    /** A service code is configured but the tax master has no rule for it. */
    public static final String NO_RULE = "NO_RULE";
    /** The order has no place of supply, so CGST/SGST cannot be told from IGST. */
    public static final String NO_PLACE_OF_SUPPLY = "NO_PLACE_OF_SUPPLY";
    public static final String RULE_APPLIED = "RULE_APPLIED";

    private final GstCalculationService gst;
    private final GstConfigurationService config;
    private final GstRoundingService rounding;

    /**
     * The frozen tax facts for one COD charge.
     *
     * @param feePaise      what the customer is charged for handling
     * @param taxablePaise  the value the tax is charged on — equal to the fee when
     *                      the basis is exclusive, less than it when inclusive
     * @param resolution    RULE_APPLIED, or why it could not be resolved
     */
    public record CodFeeTax(
            String serviceCode,
            String serviceName,
            String sacCode,
            long feePaise,
            long taxablePaise,
            Integer rateBp,
            long cgstPaise,
            long sgstPaise,
            long igstPaise,
            boolean taxInclusive,
            String resolution) {

        public long totalTaxPaise() {
            return cgstPaise + sgstPaise + igstPaise;
        }

        /** What the customer pays in total for handling: the charge, plus any tax added to it. */
        public long totalChargePaise() {
            return taxInclusive ? feePaise : feePaise + totalTaxPaise();
        }

        /** Whether the charge can be invoiced and recognised. */
        public boolean resolved() {
            return RULE_APPLIED.equals(resolution);
        }

        static CodFeeTax unresolved(String sacCode, long feePaise, String resolution) {
            return new CodFeeTax(SERVICE_CODE, SERVICE_NAME, sacCode, feePaise, feePaise,
                    null, 0, 0, 0, false, resolution);
        }
    }

    /** Raised when COD is offered but the tax configuration behind it is incomplete. */
    public static class CodTaxConfigurationException extends VeloriaException {
        public CodTaxConfigurationException(String detail) {
            super(ResponseCode.BAD_REQUEST,
                    "Cash on delivery is unavailable: its tax configuration is incomplete. "
                    + detail + " Configure it under Admin → GST.");
        }
    }

    // ── resolution ───────────────────────────────────────────────────────────

    /**
     * Prices the charge and its tax for an order, or refuses.
     *
     * @throws CodTaxConfigurationException when the configuration is incomplete
     */
    public CodFeeTax require(CustomerOrderEntity order) throws VeloriaException {
        LocalDate on = order.getOrderPlacedAt() == null
                ? LocalDate.now(IST)
                : LocalDate.ofInstant(order.getOrderPlacedAt(), IST);

        String placeOfSupply = order.getPlaceOfSupply() != null
                ? order.getPlaceOfSupply() : order.getBuyerStateCode();

        return require(placeOfSupply, order.getSellerStateCode(), on);
    }

    /**
     * The same resolution for a charge with no order behind it yet.
     *
     * <p>The checkout screen has to show what cash on delivery will cost before the
     * order exists, and it must be the figure the order will carry. One method
     * serves both so a quote and an invoice cannot drift apart.
     */
    public CodFeeTax require(String placeOfSupplyStateCode, String sellerStateCode, LocalDate on)
            throws VeloriaException {

        CodFeeTax resolved = resolve(placeOfSupplyStateCode, sellerStateCode, on);
        if (resolved.resolved()) return resolved;

        throw new CodTaxConfigurationException(switch (resolved.resolution()) {
            case NO_SAC_CONFIGURED -> "No service accounting code (SAC) is configured for the "
                    + "handling charge, so its GST rate cannot be looked up.";
            case NO_BASIS_CONFIGURED -> "It is not configured whether the handling charge "
                    + "includes GST or has GST added to it.";
            case NO_RULE -> "No active GST rule matches the configured service code "
                    + resolved.sacCode() + " for " + on + ".";
            case NO_PLACE_OF_SUPPLY -> "The order has no place of supply, so it cannot be "
                    + "determined whether the charge attracts CGST and SGST or IGST.";
            default -> "The handling charge could not be priced (" + resolved.resolution() + ").";
        });
    }

    /**
     * Prices the charge, reporting rather than throwing when it cannot.
     *
     * <p>For callers that must describe the state rather than act on it — a quote
     * that wants to tell the customer COD is unavailable, or a reconciliation that
     * reads a historical order.
     */
    public CodFeeTax resolve(String placeOfSupplyStateCode, String sellerStateCode, LocalDate on) {
        try {
            return resolveOrThrow(placeOfSupplyStateCode, sellerStateCode, on);
        } catch (IllegalStateException e) {
            // The GST engine refuses to guess CGST/SGST versus IGST without a place
            // of supply, and it is right to. Reported as an unresolved charge so the
            // caller gets the application's own validation error rather than a raw
            // IllegalStateException surfacing as a 500.
            log.warn("COD charge cannot be taxed: {}", e.getMessage());
            return CodFeeTax.unresolved(config.codFeeSac(ORGANIZATION_ID).orElse(null),
                    config.codFeePaise(ORGANIZATION_ID).orElse(0L), NO_PLACE_OF_SUPPLY);
        }
    }

    private CodFeeTax resolveOrThrow(String placeOfSupplyStateCode, String sellerStateCode,
                                     LocalDate on) {
        Long orgId = ORGANIZATION_ID;
        long feePaise = config.codFeePaise(orgId).orElse(0L);
        LocalDate when = on == null ? LocalDate.now(IST) : on;

        Optional<String> sac = config.codFeeSac(orgId);
        if (sac.isEmpty()) {
            log.warn("COD charge cannot be taxed: no {} is configured", GstConfigurationService.COD_FEE_SAC);
            return CodFeeTax.unresolved(null, feePaise, NO_SAC_CONFIGURED);
        }

        Optional<Boolean> inclusive = config.codFeeTaxInclusive(orgId);
        if (inclusive.isEmpty()) {
            log.warn("COD charge cannot be taxed: no {} is configured",
                    GstConfigurationService.COD_FEE_TAX_BASIS);
            return CodFeeTax.unresolved(sac.get(), feePaise, NO_BASIS_CONFIGURED);
        }

        // The rate comes from the tax master, by service code and date, through the
        // engine that prices everything else. A zero-rated supply and an
        // unconfigured one are different answers, and this asks for the rate before
        // knowing which it will get.
        GstCalculationService.GstResult probe = gst.calculateOnTaxableValue(
                sac.get(), com.app.master.service.core.entity.GstTaxRuleEntity.TYPE_SAC,
                Math.max(1L, feePaise), 1, placeOfSupplyStateCode, sellerStateCode, when);

        if (probe.unresolved()) {
            log.warn("COD charge cannot be taxed: no active rule matches service code {} on {}",
                    sac.get(), when);
            return CodFeeTax.unresolved(sac.get(), feePaise, NO_RULE);
        }
        if (feePaise <= 0) {
            // A zero charge has nothing to tax, but the configuration is complete —
            // so this is a resolved charge of nothing, not a failure.
            return new CodFeeTax(SERVICE_CODE, SERVICE_NAME, sac.get(), 0, 0,
                    rateBpOf(probe), 0, 0, 0, inclusive.get(), RULE_APPLIED);
        }

        int rateBp = rateBpOf(probe);
        String serviceName = config.codFeeServiceName(ORGANIZATION_ID, SERVICE_NAME);

        if (!inclusive.get()) {
            // Tax added on top of the charge. The engine prices it exactly as it
            // prices a product line: rate applied to the whole charge.
            GstCalculationService.GstResult actual = gst.calculateOnTaxableValue(
                    sac.get(), com.app.master.service.core.entity.GstTaxRuleEntity.TYPE_SAC,
                    feePaise, 1, placeOfSupplyStateCode, sellerStateCode, when);
            return new CodFeeTax(SERVICE_CODE, serviceName, sac.get(), feePaise, feePaise, rateBp,
                    actual.cgstAmount(), actual.sgstAmount(), actual.igstAmount(),
                    false, RULE_APPLIED);
        }

        // Tax already inside the charge.
        //
        // The tax is the REMAINDER, not a second rate application. Carving the base
        // out and then taxing that base rounds twice and the two halves stop adding
        // up: ₹50 at 18% carves to 4237, whose tax is 762, and 4237 + 762 is 4999.
        // The customer pays ₹50, so declaring ₹49.99 on the invoice is a
        // reconciliation error on a statutory document. Taking the tax as what is
        // left makes base + tax equal the charge by construction.
        long taxable = taxableWithinInclusive(feePaise, rateBp);
        long totalTax = feePaise - taxable;

        long cgst = 0, sgst = 0, igst = 0;
        if (probe.interState()) {
            igst = totalTax;
        } else {
            // Half each, with SGST absorbing the odd paisa so the two heads sum to
            // the tax exactly. Which head takes it is arbitrary but must be fixed,
            // or the same charge would reconcile differently on different days.
            cgst = totalTax / 2;
            sgst = totalTax - cgst;
        }

        return new CodFeeTax(SERVICE_CODE, serviceName, sac.get(), feePaise, taxable, rateBp,
                cgst, sgst, igst, true, RULE_APPLIED);
    }

    /** The combined rate, whichever heads the supply attracts. */
    private static int rateBpOf(GstCalculationService.GstResult r) {
        return r.interState() ? r.igstRateBp() : r.cgstRateBp() + r.sgstRateBp();
    }

    /**
     * The taxable value inside a tax-inclusive charge.
     *
     * <pre>
     *   taxable = gross × 10000 / (10000 + rateBp)
     * </pre>
     *
     * <p>Integer paise throughout, rounded by the same HALF_UP policy the rest of
     * the tax engine uses, so the carved-out base and the tax on it sum back to the
     * charge the customer was quoted.
     */
    private long taxableWithinInclusive(long grossPaise, int rateBp) {
        if (rateBp <= 0) return grossPaise;
        return rounding.proportionalShare(grossPaise, 10_000L, 10_000L + rateBp);
    }
}
