package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstPaymentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GstPaymentRepository extends JpaRepository<GstPaymentEntity, Long> {

    boolean existsByPaymentReference(String paymentReference);

    List<GstPaymentEntity> findByOrganizationIdAndTaxPeriodOrderByIdAsc(Long organizationId, String taxPeriod);

    List<GstPaymentEntity> findByOrganizationIdOrderByIdDesc(Long organizationId);

    @Query("""
        SELECT COALESCE(SUM(p.cgstPaise),0), COALESCE(SUM(p.sgstPaise),0),
               COALESCE(SUM(p.igstPaise),0), COALESCE(SUM(p.interestPaise),0),
               COALESCE(SUM(p.lateFeePaise),0), COALESCE(SUM(p.totalPaise),0)
        FROM GstPaymentEntity p
        WHERE p.organizationId = :orgId AND p.taxPeriod = :period
    """)
    List<Object[]> sumForPeriod(@Param("orgId") Long orgId, @Param("period") String period);
}
