package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstOutputTaxEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GstOutputTaxRepository extends JpaRepository<GstOutputTaxEntity, Long> {

    Optional<GstOutputTaxEntity> findByCustomerOrderUuid(UUID customerOrderUuid);

    Optional<GstOutputTaxEntity> findByCustomerOrderId(Long customerOrderId);

    List<GstOutputTaxEntity> findByTaxPeriodOrderByCreatedAtDesc(String taxPeriod);

    List<GstOutputTaxEntity> findAllByOrderByCreatedAtDesc();

    @Query("SELECT g FROM GstOutputTaxEntity g WHERE g.financialYear = :fy ORDER BY g.taxPeriod ASC")
    List<GstOutputTaxEntity> findByFinancialYear(@Param("fy") String financialYear);

    @Modifying
    @Query(nativeQuery = true, value = """
        INSERT INTO gst_output_tax (
            customer_order_id, customer_order_uuid, order_code, customer_name, customer_gstin,
            place_of_supply_state_code, supply_type, financial_year, tax_period, invoice_date,
            taxable_value, cgst_rate, cgst_amount, sgst_rate, sgst_amount,
            igst_rate, igst_amount, total_output_tax, invoice_value, return_status,
            created_at, updated_at
        ) VALUES (
            :orderId, :orderUuid, :orderCode, :customerName, :gstin,
            :placeOfSupply, :supplyType, :fy, :period, :invoiceDate,
            :taxableValue, :cgstRate, :cgstAmount, :sgstRate, :sgstAmount,
            :igstRate, :igstAmount, :totalTax, :invoiceValue, :returnStatus,
            NOW(), NOW()
        ) ON CONFLICT (customer_order_uuid) DO NOTHING
        """)
    void insertIfAbsent(
        @Param("orderId") Long orderId,
        @Param("orderUuid") UUID orderUuid,
        @Param("orderCode") String orderCode,
        @Param("customerName") String customerName,
        @Param("gstin") String gstin,
        @Param("placeOfSupply") String placeOfSupply,
        @Param("supplyType") String supplyType,
        @Param("fy") String fy,
        @Param("period") String period,
        @Param("invoiceDate") LocalDate invoiceDate,
        @Param("taxableValue") Long taxableValue,
        @Param("cgstRate") BigDecimal cgstRate,
        @Param("cgstAmount") Long cgstAmount,
        @Param("sgstRate") BigDecimal sgstRate,
        @Param("sgstAmount") Long sgstAmount,
        @Param("igstRate") BigDecimal igstRate,
        @Param("igstAmount") Long igstAmount,
        @Param("totalTax") Long totalTax,
        @Param("invoiceValue") Long invoiceValue,
        @Param("returnStatus") String returnStatus
    );

    @Query(nativeQuery = true, value = """
        SELECT
          tax_period,
          SUM(taxable_value)   AS total_taxable,
          SUM(cgst_amount)     AS total_cgst,
          SUM(sgst_amount)     AS total_sgst,
          SUM(igst_amount)     AS total_igst,
          SUM(total_output_tax) AS total_gst,
          SUM(invoice_value)   AS total_invoice_value
        FROM gst_output_tax
        WHERE (:period IS NULL OR tax_period = :period)
        GROUP BY tax_period
        ORDER BY tax_period DESC
        """)
    List<Object[]> aggregateByPeriod(@Param("period") String period);
}
