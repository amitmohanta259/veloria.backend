package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstItcTransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GstItcTransactionRepository extends JpaRepository<GstItcTransactionEntity, Long> {

    boolean existsByTransactionReference(String transactionReference);

    Optional<GstItcTransactionEntity> findByTransactionReference(String transactionReference);

    List<GstItcTransactionEntity> findByInputTaxIdOrderByIdAsc(Long inputTaxId);

    List<GstItcTransactionEntity> findByOrganizationIdAndTaxPeriodOrderByIdAsc(Long organizationId, String taxPeriod);

    @Query("""
        SELECT t FROM GstItcTransactionEntity t
        WHERE t.organizationId = :orgId
          AND (:period IS NULL OR t.taxPeriod = :period)
          AND (:type IS NULL OR t.transactionType = :type)
        ORDER BY t.id DESC
    """)
    Page<GstItcTransactionEntity> findFiltered(@Param("orgId") Long orgId,
                                               @Param("period") String period,
                                               @Param("type") String type,
                                               Pageable pageable);

    @Query("""
        SELECT COALESCE(SUM(t.cgstPaise),0), COALESCE(SUM(t.sgstPaise),0),
               COALESCE(SUM(t.igstPaise),0), COALESCE(SUM(t.totalPaise),0)
        FROM GstItcTransactionEntity t
        WHERE t.organizationId = :orgId
          AND t.transactionType = :type
          AND (:period IS NULL OR t.taxPeriod = :period)
    """)
    List<Object[]> sumByType(@Param("orgId") Long orgId,
                             @Param("type") String type,
                             @Param("period") String period);
}
