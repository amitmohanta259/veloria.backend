package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstInputTaxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GstInputTaxRepository extends JpaRepository<GstInputTaxEntity, Long> {

    Optional<GstInputTaxEntity> findByPurchaseOrderUuid(UUID purchaseOrderUuid);

    List<GstInputTaxEntity> findByTaxPeriodOrderByCreatedAtDesc(String taxPeriod);

    List<GstInputTaxEntity> findAllByOrderByCreatedAtDesc();

    List<GstInputTaxEntity> findByItcEligibilityOrderByCreatedAtDesc(String itcEligibility);

    @Query("SELECT g FROM GstInputTaxEntity g WHERE g.financialYear = :fy ORDER BY g.taxPeriod ASC")
    List<GstInputTaxEntity> findByFinancialYear(@Param("fy") String financialYear);

    @Query(nativeQuery = true, value = """
        SELECT
          tax_period,
          SUM(taxable_value) AS total_taxable,
          SUM(cgst_amount)   AS total_cgst,
          SUM(sgst_amount)   AS total_sgst,
          SUM(igst_amount)   AS total_igst,
          SUM(total_input_tax) AS total_gst,
          SUM(CASE WHEN itc_eligibility = 'ELIGIBLE' THEN total_input_tax ELSE 0 END) AS eligible_itc
        FROM gst_input_tax
        WHERE (:period IS NULL OR tax_period = :period)
        GROUP BY tax_period
        ORDER BY tax_period DESC
        """)
    List<Object[]> aggregateByPeriod(@Param("period") String period);
}
