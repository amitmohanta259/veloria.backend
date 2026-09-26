package com.app.master.service.service.payment;

import com.app.master.service.core.entity.CustomerOrderEntity;
import com.app.master.service.service.admin.GstCalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The tax on the cash-on-delivery handling charge.
 *
 * <p>The charge is approved as taxable income of the business. What rate applies
 * is a tax question, and this does not answer it: the rate comes from the same
 * {@code gst_tax_rules} master that prices every product, looked up by the
 * service code configured for the charge and by the order's own date, so a
 * historical order reproduces the rate that applied when it was placed.
 *
 * <p><b>No rate is hardcoded here, and none is assumed.</b> Two outcomes are
 * deliberately different:
 *
 * <ul>
 *   <li><b>{@code RULE_APPLIED}</b> — the master priced it. The figures are
 *       auditable and the charge can be recognised.</li>
 *   <li><b>{@code NOT_CONFIGURED} / {@code NO_RULE}</b> — no service code is
 *       configured for the charge, or the master has no rule for it. This is not
 *       0%: it is an unanswered tax question, and the charge is left
 *       unrecognised rather than posted at a rate nobody approved.</li>
 * </ul>
 *
 * <p>Setting {@code veloria.payment.cod-fee.sac-code} is therefore a tax
 * decision, not a configuration convenience. It is intentionally unset: see the
 * COD-fee tax blocker in the P0-11 report.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodFeeTaxResolver {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";

    private final GstCalculationService gst;

    /**
     * The service code the COD handling charge is taxed under. Unset by default
     * — no code is invented and no rate is substituted for one.
     */
    @Value("${veloria.payment.cod-fee.sac-code:}")
    private String sacCode;

    /**
     * The frozen tax facts for a COD charge.
     *
     * @param resolution RULE_APPLIED, NOT_CONFIGURED, NO_RULE or NO_HSN
     */
    public record CodFeeTax(
            String sacCode,
            long taxablePaise,
            Integer rateBp,
            long cgstPaise,
            long sgstPaise,
            long igstPaise,
            String resolution) {

        public long totalTaxPaise() {
            return cgstPaise + sgstPaise + igstPaise;
        }

        /** Whether the charge can be recognised in the books. */
        public boolean resolved() {
            return "RULE_APPLIED".equals(resolution);
        }

        static CodFeeTax unresolved(String sacCode, long taxable, String resolution) {
            return new CodFeeTax(sacCode, taxable, null, 0, 0, 0, resolution);
        }
    }

    /**
     * Prices the tax on this order's COD charge.
     *
     * <p>The charge is treated as the taxable value of a service supplied
     * alongside the goods, so the tax is added to it rather than carved out of
     * it. Which of those two the approved ₹50 means is itself an open question —
     * recorded as a blocker — and is why nothing is recognised until a rule
     * resolves.
     */
    public CodFeeTax resolve(CustomerOrderEntity order, long feePaise) {
        LocalDate on = order.getOrderPlacedAt() == null
                ? LocalDate.now()
                : LocalDate.ofInstant(order.getOrderPlacedAt(), IST);

        return resolve(feePaise,
                order.getPlaceOfSupply() != null ? order.getPlaceOfSupply() : order.getBuyerStateCode(),
                order.getSellerStateCode(), on, "order " + order.getOrderCode());
    }

    /**
     * The same resolution for a charge that has no order behind it yet.
     *
     * <p>The checkout screen has to show the customer what cash on delivery will
     * cost <em>before</em> the order exists, and it must be the same figure the
     * order will carry. One method serves both so the two cannot drift: a quote the
     * browser shows and an invoice the customer is charged must come from the same
     * arithmetic, or the second is a surprise.
     */
    public CodFeeTax resolve(long feePaise, String placeOfSupplyStateCode,
                             String sellerStateCode, LocalDate on) {
        return resolve(feePaise, placeOfSupplyStateCode, sellerStateCode, on, "a prospective order");
    }

    private CodFeeTax resolve(long feePaise, String placeOfSupplyStateCode, String sellerStateCode,
                              LocalDate on, String what) {
        if (feePaise <= 0) {
            return CodFeeTax.unresolved(null, 0, NOT_CONFIGURED);
        }
        if (sacCode == null || sacCode.isBlank()) {
            log.warn("COD charge of {} paise on {} has no configured service code, so its tax "
                   + "cannot be resolved; the charge is not being recognised", feePaise, what);
            return CodFeeTax.unresolved(null, feePaise, NOT_CONFIGURED);
        }

        GstCalculationService.GstResult r = gst.calculateOnTaxableValue(
                sacCode, feePaise, 1, placeOfSupplyStateCode, sellerStateCode,
                on == null ? LocalDate.now() : on);

        if (r.unresolved()) {
            log.warn("No tax rule matches the COD service code {} on {}; the charge on {} "
                   + "is not being recognised", sacCode, on, what);
            return CodFeeTax.unresolved(sacCode, feePaise, r.resolution());
        }

        int rateBp = r.interState()
                ? r.igstRateBp()
                : r.cgstRateBp() + r.sgstRateBp();

        return new CodFeeTax(sacCode, feePaise, rateBp,
                r.cgstAmount(), r.sgstAmount(), r.igstAmount(), r.resolution());
    }
}
