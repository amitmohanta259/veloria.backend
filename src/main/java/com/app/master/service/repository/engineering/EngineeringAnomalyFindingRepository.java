package com.app.master.service.repository.engineering;

import com.app.master.service.core.entity.EngineeringAnomalyFindingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EngineeringAnomalyFindingRepository extends JpaRepository<EngineeringAnomalyFindingEntity, Long> {

    Optional<EngineeringAnomalyFindingEntity> findByUuid(UUID uuid);

    Optional<EngineeringAnomalyFindingEntity> findByAnomalyFingerprint(String fingerprint);

    List<EngineeringAnomalyFindingEntity> findByScanIdOrderBySeverityAscIdAsc(Long scanId);

    List<EngineeringAnomalyFindingEntity> findByTransactionIdOrderByDetectedAtDesc(String transactionId);

    List<EngineeringAnomalyFindingEntity> findByProductIdOrderByDetectedAtDesc(String productId);

    /**
     * The anomaly list, filtered.
     *
     * <p>Every filter is a typed parameter and every one is optional. Note what is
     * absent: there is no table or column filter, because those values are encrypted
     * and §33 forbids accepting them as query parameters. Filtering happens on
     * severity, domain, status and transaction — which is why those columns are
     * deliberately not encrypted.
     */
    @Query("""
           SELECT f FROM EngineeringAnomalyFindingEntity f
            WHERE (:severity      IS NULL OR f.severity      = :severity)
              AND (:domain        IS NULL OR f.domain        = :domain)
              AND (:status        IS NULL OR f.status        = :status)
              AND (:transactionId IS NULL OR f.transactionId = :transactionId)
              AND (:scanId        IS NULL OR f.scanId        = :scanId)
            ORDER BY f.detectedAt DESC, f.id DESC
           """)
    Page<EngineeringAnomalyFindingEntity> search(@Param("severity") String severity,
                                                 @Param("domain") String domain,
                                                 @Param("status") String status,
                                                 @Param("transactionId") String transactionId,
                                                 @Param("scanId") Long scanId,
                                                 Pageable pageable);

    @Query("SELECT f.domain, COUNT(f) FROM EngineeringAnomalyFindingEntity f GROUP BY f.domain")
    List<Object[]> countByDomain();

    @Query("SELECT f.severity, COUNT(f) FROM EngineeringAnomalyFindingEntity f WHERE f.status = 'OPEN' GROUP BY f.severity")
    List<Object[]> countOpenBySeverity();

    long countByStatus(String status);
}
