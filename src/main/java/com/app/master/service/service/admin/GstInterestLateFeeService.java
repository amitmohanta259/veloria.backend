package com.app.master.service.service.admin;

import com.app.master.service.core.entity.GstInterestRuleEntity;
import com.app.master.service.core.entity.GstLateFeeRuleEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.security.GstSecurityContext;
import com.app.master.service.repository.admin.GstInterestRuleRepository;
import com.app.master.service.repository.admin.GstLateFeeRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Interest and late fee, computed only from configured statutory rates.
 *
 * <b>Nothing here has a default rate.</b> Interest under the GST Act has been
 * changed by notification more than once, and a rate compiled into the code
 * would quietly misstate a liability for any period it did not apply to. When
 * no rule covers the date in question the answer is
 * {@code REQUIRES_CONFIGURATION} with the reason — never a plausible number.
 *
 * Rules are versioned by effective date, so a computation for an old period
 * uses the rate that applied then, exactly as historical GST rates do.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstInterestLateFeeService {

    public static final String REQUIRES_CONFIGURATION = "REQUIRES_CONFIGURATION";
    public static final String COMPUTED               = "COMPUTED";

    private static final BigDecimal BP_DIVISOR  = BigDecimal.valueOf(10000);
    private static final BigDecimal DAYS_A_YEAR = BigDecimal.valueOf(365);

    private final GstInterestRuleRepository interestRepo;
    private final GstLateFeeRuleRepository lateFeeRepo;
    private final GstIdentityService identityService;
    private final GstAuditService auditService;
    private final GstSecurityContext securityContext;

    private Long orgId() {
        return securityContext.current()
                .map(com.app.master.service.core.security.GstPrincipal::organizationId)
                .filter(Objects::nonNull)
                .orElseGet(identityService::defaultOrganizationId);
    }

    /**
     * The outcome of a computation.
     *
     * {@code status} is {@link #COMPUTED} only when a rule actually covered the
     * date; otherwise it is {@link #REQUIRES_CONFIGURATION} and {@code amountPaise}
     * is null rather than zero — a missing rate is not the same as no liability.
     */
    public record Computation(String status, Long amountPaise, Integer rateBp,
                              String ruleSource, String detail) {

        public static Computation requiresConfiguration(String detail) {
            return new Computation(REQUIRES_CONFIGURATION, null, null, null, detail);
        }

        public static Computation computed(long paise, Integer rateBp, String source, String detail) {
            return new Computation(COMPUTED, paise, rateBp, source, detail);
        }
    }

    // ── Interest ─────────────────────────────────────────────────────────────

    /**
     * Simple daily interest on an unpaid amount.
     *
     * @param ruleType which statutory rate applies, e.g. {@code LATE_PAYMENT}
     * @param onDate   the date the liability arose — the rate in force then is used
     */
    public Computation interest(String ruleType, long outstandingPaise, int days, LocalDate onDate) {
        if (days <= 0 || outstandingPaise <= 0) {
            return Computation.computed(0L, null, null, "Nothing outstanding, so no interest arises");
        }

        Optional<GstInterestRuleEntity> rule = interestRepo
                .findByOrganizationIdAndRuleTypeAndActiveTrueOrderByEffectiveFromDesc(orgId(), ruleType)
                .stream()
                .filter(r -> covers(r.getEffectiveFrom(), r.getEffectiveTo(), onDate))
                .findFirst();

        if (rule.isEmpty()) {
            return Computation.requiresConfiguration(
                    "No active interest rule of type '" + ruleType + "' covers " + onDate
                    + ". Configure the statutory rate, with the notification it comes from, "
                    + "before interest can be computed. This application does not assume a rate.");
        }

        GstInterestRuleEntity r = rule.get();
        long amount = BigDecimal.valueOf(outstandingPaise)
                .multiply(BigDecimal.valueOf(r.getRateBp()))
                .divide(BP_DIVISOR, 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(days))
                .divide(DAYS_A_YEAR, 0, RoundingMode.HALF_UP)
                .longValue();

        return Computation.computed(amount, r.getRateBp(), r.getSourceReference(),
                "Simple interest at " + (r.getRateBp() / 100.0) + "% a year for " + days + " day(s)");
    }

    // ── Late fee ─────────────────────────────────────────────────────────────

    /** Per-day late fee for a return, capped where the rule sets a cap. */
    public Computation lateFee(String returnType, int daysLate, LocalDate onDate) {
        if (daysLate <= 0) {
            return Computation.computed(0L, null, null, "Filed on time, so no late fee arises");
        }

        Optional<GstLateFeeRuleEntity> rule = lateFeeRepo
                .findByOrganizationIdAndReturnTypeAndActiveTrueOrderByEffectiveFromDesc(orgId(), returnType)
                .stream()
                .filter(r -> covers(r.getEffectiveFrom(), r.getEffectiveTo(), onDate))
                .findFirst();

        if (rule.isEmpty()) {
            return Computation.requiresConfiguration(
                    "No active late-fee rule for " + returnType + " covers " + onDate
                    + ". Configure the statutory per-day fee, with its source, before a late fee "
                    + "can be computed.");
        }

        GstLateFeeRuleEntity r = rule.get();
        long amount = r.getPerDayPaise() * (long) daysLate;
        String detail = daysLate + " day(s) at " + r.getPerDayPaise() + " paise a day";
        if (r.getMaxPaise() != null && amount > r.getMaxPaise()) {
            amount = r.getMaxPaise();
            detail += ", capped at the statutory maximum";
        }
        return Computation.computed(amount, null, r.getSourceReference(), detail);
    }

    // ── Configuration ────────────────────────────────────────────────────────

    /** What is configured, and therefore what can currently be computed. */
    public Map<String, Object> configuration() {
        List<GstInterestRuleEntity> interest =
                interestRepo.findByOrganizationIdAndActiveTrueOrderByEffectiveFromDesc(orgId());
        List<GstLateFeeRuleEntity> fees =
                lateFeeRepo.findByOrganizationIdAndActiveTrueOrderByEffectiveFromDesc(orgId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("interestRules", interest);
        out.put("lateFeeRules", fees);
        out.put("interestStatus", interest.isEmpty() ? REQUIRES_CONFIGURATION : "CONFIGURED");
        out.put("lateFeeStatus", fees.isEmpty() ? REQUIRES_CONFIGURATION : "CONFIGURED");
        out.put("note", "Statutory rates are configuration, not code. Each rule records the "
                + "notification it came from so a computation can be traced back to its source.");
        return out;
    }

    /**
     * Adds an interest rule. A source reference is mandatory: a rate nobody can
     * trace back to a notification is not auditable, and an unauditable rate is
     * indistinguishable from an invented one.
     */
    @Transactional(rollbackFor = Exception.class)
    public GstInterestRuleEntity addInterestRule(String ruleType, int rateBp, LocalDate effectiveFrom,
                                                 LocalDate effectiveTo, String sourceReference,
                                                 String description) throws VeloriaException {
        requireSource(sourceReference);
        if (rateBp <= 0) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "An interest rate must be greater than zero");
        }
        if (effectiveFrom == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "An effective-from date is required so the rate applies to the right periods");
        }

        GstInterestRuleEntity saved = interestRepo.save(GstInterestRuleEntity.builder()
                .organizationId(orgId())
                .ruleType(ruleType)
                .rateBp(rateBp)
                .calculationMethod("SIMPLE_DAILY")
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .sourceReference(sourceReference)
                .description(description)
                .active(true)
                .build());

        auditService.log("GST_INTEREST_RULE", saved.getId(), ruleType, "INTEREST_RULE_ADDED",
                null, null, rateBp + " bp from " + effectiveFrom, null, securityContext.actor());
        return saved;
    }

    /** Adds a late-fee rule, under the same sourcing requirement. */
    @Transactional(rollbackFor = Exception.class)
    public GstLateFeeRuleEntity addLateFeeRule(String returnType, long perDayPaise, Long maxPaise,
                                               LocalDate effectiveFrom, LocalDate effectiveTo,
                                               String sourceReference, String description)
            throws VeloriaException {
        requireSource(sourceReference);
        if (perDayPaise <= 0) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A late fee must be greater than zero");
        }
        if (effectiveFrom == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "An effective-from date is required");
        }

        GstLateFeeRuleEntity saved = lateFeeRepo.save(GstLateFeeRuleEntity.builder()
                .organizationId(orgId())
                .returnType(returnType)
                .perDayPaise(perDayPaise)
                .maxPaise(maxPaise)
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .sourceReference(sourceReference)
                .description(description)
                .active(true)
                .build());

        auditService.log("GST_LATE_FEE_RULE", saved.getId(), returnType, "LATE_FEE_RULE_ADDED",
                null, null, perDayPaise + " paise/day from " + effectiveFrom, null,
                securityContext.actor());
        return saved;
    }

    private void requireSource(String sourceReference) throws VeloriaException {
        if (sourceReference == null || sourceReference.isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A source reference is required — the notification or circular this rate comes "
                    + "from. A rate that cannot be traced to its source cannot be audited.");
        }
    }

    private boolean covers(LocalDate from, LocalDate to, LocalDate on) {
        LocalDate d = on == null ? LocalDate.now() : on;
        if (from != null && d.isBefore(from)) return false;
        return to == null || !d.isAfter(to);
    }
}
