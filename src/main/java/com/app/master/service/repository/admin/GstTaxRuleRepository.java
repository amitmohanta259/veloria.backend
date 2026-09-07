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

    Optional<GstTaxRuleEntity> findByUuidAndActiveTrue(UUID uuid);

    @Query("""
        SELECT r FROM GstTaxRuleEntity r
        WHERE r.active = true
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
    List<GstTaxRuleEntity> findMatchingRules(
            @Param("hsnCode") String hsnCode,
            @Param("pricePaise") long pricePaise,
            @Param("date") LocalDate date
    );
}
