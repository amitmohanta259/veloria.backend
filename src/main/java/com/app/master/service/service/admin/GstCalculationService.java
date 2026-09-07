package com.app.master.service.service.admin;

import com.app.master.service.core.entity.GstTaxRuleEntity;
import com.app.master.service.repository.admin.GstTaxRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GstCalculationService {

    private final GstTaxRuleRepository taxRuleRepository;
    private final GstRoundingService rounding;
    private final GstIdentityService identityService;

    public record GstResult(
            String hsnCode,
            long pricePaise,
            /** quantity * unit price — the value tax is charged on */
            long taxableValuePaise,
            int quantity,
            int cgstRateBp,
            int sgstRateBp,
            int igstRateBp,
            int cessRateBp,
            long cgstAmount,
            long sgstAmount,
            long igstAmount,
            long cessAmount,
            long totalTax,
            boolean interState,
            /** RULE_APPLIED, NO_HSN, NO_RULE — so callers can distinguish 0% from unconfigured */
            String resolution
    ) {
        public static GstResult zero(String hsnCode, long pricePaise, long taxable, int qty, String resolution) {
            return new GstResult(hsnCode, pricePaise, taxable, qty, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, resolution);
        }

        /** True when GST could not be resolved, as opposed to a genuine 0% rate. */
        public boolean unresolved() {
            return "NO_HSN".equals(resolution) || "NO_RULE".equals(resolution);
        }
    }

    /**
     * Calculate GST for a single line item.
     *
     * @param hsnCode        HSN code of the product (null = no tax)
     * @param sellingPaise   selling price in paise (tax-exclusive base)
     * @param buyerStateCode 2-digit state code of buyer
     * @param sellerStateCode 2-digit state code of seller
     * @param effectiveDate  date to use for rule lookup (typically order date)
     */
    public GstResult calculate(
            String hsnCode,
            long sellingPaise,
            String buyerStateCode,
            String sellerStateCode,
            LocalDate effectiveDate
    ) {
        return calculate(hsnCode, sellingPaise, 1, buyerStateCode, sellerStateCode, effectiveDate);
    }

    /**
     * Quantity-aware line calculation.
     *
     * Tax is charged on quantity * unit price, not on the unit price alone —
     * the previous single-argument form silently taxed one unit regardless of
     * how many were ordered.
     *
     * @param placeOfSupplyStateCode place of supply; must be resolved by the caller
     */
    public GstResult calculate(
            String hsnCode,
            long sellingPaise,
            int quantity,
            String placeOfSupplyStateCode,
            String sellerStateCode,
            LocalDate effectiveDate
    ) {
        int qty = Math.max(1, quantity);
        long taxable = rounding.taxableValue(sellingPaise, qty);

        if (hsnCode == null || hsnCode.isBlank()) {
            // Not the same as 0% — the product has no GST classification at all.
            log.warn("Product has no HSN code; GST cannot be resolved (price={} qty={})", sellingPaise, qty);
            return GstResult.zero(null, sellingPaise, taxable, qty, "NO_HSN");
        }

        List<GstTaxRuleEntity> rules = taxRuleRepository.findMatchingRules(
                hsnCode, sellingPaise, effectiveDate != null ? effectiveDate : LocalDate.now()
        );

        if (rules.isEmpty()) {
            log.warn("No GST rule matches HSN={} price={} on {} — treating as unresolved, not 0%",
                    hsnCode, sellingPaise, effectiveDate);
            return GstResult.zero(hsnCode, sellingPaise, taxable, qty, "NO_RULE");
        }

        GstTaxRuleEntity rule = rules.get(0); // highest priority first
        boolean interState = identityService.isInterState(placeOfSupplyStateCode, sellerStateCode);

        long cgstAmount = 0;
        long sgstAmount = 0;
        long igstAmount = 0;

        if (interState) {
            igstAmount = rounding.taxOn(taxable, rule.getIgstRateBp());
        } else {
            cgstAmount = rounding.taxOn(taxable, rule.getCgstRateBp());
            sgstAmount = rounding.taxOn(taxable, rule.getSgstRateBp());
        }

        // Cess applies only when the matched rule carries a rate. Nothing here
        // assumes cess for any product category.
        int cessRateBp = rule.getCessRateBp() != null ? rule.getCessRateBp() : 0;
        long cessAmount = rounding.taxOn(taxable, cessRateBp);

        return new GstResult(
                hsnCode,
                sellingPaise,
                taxable,
                qty,
                interState ? 0 : rule.getCgstRateBp(),
                interState ? 0 : rule.getSgstRateBp(),
                interState ? rule.getIgstRateBp() : 0,
                cessRateBp,
                cgstAmount,
                sgstAmount,
                igstAmount,
                cessAmount,
                cgstAmount + sgstAmount + igstAmount + cessAmount,
                interState,
                "RULE_APPLIED"
        );
    }

    /**
     * Calculates tax on an already-determined taxable value.
     *
     * The invoice layer computes the base itself (gross minus discount, plus any
     * apportioned shipping), so it applies the rate to that figure rather than
     * having this service re-derive it from a unit price.
     *
     * The rate is still selected from the tax master by HSN, price slab and
     * transaction date, so historical rates are preserved.
     */
    public GstResult calculateOnTaxableValue(
            String hsnCode,
            long taxableValuePaise,
            int quantity,
            String placeOfSupplyStateCode,
            String sellerStateCode,
            LocalDate effectiveDate
    ) {
        int qty = Math.max(1, quantity);
        long slabPrice = taxableValuePaise / qty;

        if (hsnCode == null || hsnCode.isBlank()) {
            log.warn("Product has no HSN code; GST cannot be resolved (taxable={})", taxableValuePaise);
            return GstResult.zero(null, slabPrice, taxableValuePaise, qty, "NO_HSN");
        }

        List<GstTaxRuleEntity> rules = taxRuleRepository.findMatchingRules(
                hsnCode, slabPrice, effectiveDate != null ? effectiveDate : LocalDate.now());

        if (rules.isEmpty()) {
            log.warn("No GST rule matches HSN={} price={} on {} — treating as unresolved, not 0%",
                    hsnCode, slabPrice, effectiveDate);
            return GstResult.zero(hsnCode, slabPrice, taxableValuePaise, qty, "NO_RULE");
        }

        GstTaxRuleEntity rule = rules.get(0);
        boolean interState = identityService.isInterState(placeOfSupplyStateCode, sellerStateCode);

        long cgst = interState ? 0 : rounding.taxOn(taxableValuePaise, rule.getCgstRateBp());
        long sgst = interState ? 0 : rounding.taxOn(taxableValuePaise, rule.getSgstRateBp());
        long igst = interState ? rounding.taxOn(taxableValuePaise, rule.getIgstRateBp()) : 0;

        int cessRateBp = rule.getCessRateBp() != null ? rule.getCessRateBp() : 0;
        long cess = rounding.taxOn(taxableValuePaise, cessRateBp);

        return new GstResult(
                hsnCode, slabPrice, taxableValuePaise, qty,
                interState ? 0 : rule.getCgstRateBp(),
                interState ? 0 : rule.getSgstRateBp(),
                interState ? rule.getIgstRateBp() : 0,
                cessRateBp,
                cgst, sgst, igst, cess,
                cgst + sgst + igst + cess,
                interState,
                "RULE_APPLIED");
    }
}
