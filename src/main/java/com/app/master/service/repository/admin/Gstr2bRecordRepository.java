package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.Gstr2bRecordEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface Gstr2bRecordRepository extends JpaRepository<Gstr2bRecordEntity, Long> {

    List<Gstr2bRecordEntity> findByImportIdOrderByIdAsc(Long importId);

    List<Gstr2bRecordEntity> findByOrganizationIdAndTaxPeriodOrderByIdAsc(Long organizationId, String taxPeriod);

    List<Gstr2bRecordEntity> findBySupplierGstinAndInvoiceNumber(String supplierGstin, String invoiceNumber);

    @Query("""
        SELECT r FROM Gstr2bRecordEntity r
        WHERE r.organizationId = :orgId
          AND (:period IS NULL OR r.taxPeriod = :period)
          AND (:status IS NULL OR r.matchStatus = :status)
        ORDER BY r.id DESC
    """)
    Page<Gstr2bRecordEntity> findFiltered(@Param("orgId") Long orgId,
                                          @Param("period") String period,
                                          @Param("status") String status,
                                          Pageable pageable);

    long countByOrganizationIdAndTaxPeriodAndMatchStatus(Long organizationId, String taxPeriod, String matchStatus);
}
