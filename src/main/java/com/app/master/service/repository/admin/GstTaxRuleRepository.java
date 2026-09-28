package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstTaxRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GstTaxRuleRepository extends JpaRepository<GstTaxRuleEntity, Long> {

    List<GstTaxRuleEntity> findByActiveTrueOrderByPriorityDescHsnCode();

    /** Every rule, active ones first, so a withdrawn rule is still visible. */
    List<GstTaxRuleEntity> findAllByOrderByActiveDescPriorityDescHsnCode();

    Optional<GstTaxRuleEntity> findByUuidAndActiveTrue(UUID uuid);

    /**
     * A rule whatever its state.
     *
     * <p>Needed to toggle one: looking a rule up with {@code AndActiveTrue} and then
     * flipping its flag can only ever switch a rule off, because once it is off the
     * lookup no longer finds it. A deactivated rule could not be brought back.
     */
    Optional<GstTaxRuleEntity> findByUuid(UUID uuid);

    /**
     * The rules that price a code on a date, highest priority first.
     *
     * <p>Filtered on {@code taxCodeType} as well as the code itself, so a rule for a
     * goods HSN can never price a service and a rule for a SAC can never price a
     * product. Without that filter the two are indistinguishable, and a
     * misconfigured service code would quietly be priced at a garment's rate —
     * which is a misdeclaration, not an approximation.
     *
     * <p>Everything else this applies is unchanged and is the single precedence
     * mechanism in the application: active only, both effective-date bounds, the
     * optional price slab, and {@code ORDER BY priority DESC}.
     */
    @Query("""
        SELECT r FROM GstTaxRuleEntity r
        WHERE r.active = true
          AND r.taxCodeType = :taxCodeType
          AND r.effectiveFrom <= :date
          AND (r.effectiveTo IS NULL OR r.effectiveTo >= :date)
          AND (
            (r.hsnMatchType = 'EXACT' AND r.hsnCode = :hsnCode)
            OR (r.hsnMatchType = 'PREFIX' AND :hsnCode LIKE CONCAT(r.hsnCode, '%'))
          )
          AND (r.minPricePaise IS NULL OR r.minPricePaise <= :pricePaise)
          AND (r.maxPricePaise IS NULL OR r.maxPricePaise >= :pricePaise)
        ORDER BY r.priority DESC
        """)
    List<GstTaxRuleEntity> findMatchingRulesOfType(
            @Param("hsnCode") String hsnCode,
            @Param("taxCodeType") String taxCodeType,
            @Param("pricePaise") long pricePaise,
            @Param("date") LocalDate date
    );

    /** Goods. The default for every caller that was written before services were taxed. */
    default List<GstTaxRuleEntity> findMatchingRules(String hsnCode, long pricePaise, LocalDate date) {
        return findMatchingRulesOfType(hsnCode, GstTaxRuleEntity.TYPE_HSN, pricePaise, date);
    }
}
