package com.app.master.service.repository.engineering;

import com.app.master.service.core.entity.EngineeringAnomalyScanEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EngineeringAnomalyScanRepository extends JpaRepository<EngineeringAnomalyScanEntity, Long> {

    Optional<EngineeringAnomalyScanEntity> findByScanNumber(String scanNumber);

    Optional<EngineeringAnomalyScanEntity> findByUuid(UUID uuid);

    Page<EngineeringAnomalyScanEntity> findAllByOrderByRequestedAtDesc(Pageable pageable);

    /** The scan the dashboard shows as "last scan", whatever its outcome. */
    Optional<EngineeringAnomalyScanEntity> findFirstByOrderByRequestedAtDesc();

    /** The last scan that actually finished — what the summary cards are drawn from. */
    @Query("""
           SELECT s FROM EngineeringAnomalyScanEntity s
            WHERE s.status IN ('COMPLETED', 'PARTIAL')
            ORDER BY s.requestedAt DESC
           """)
    List<EngineeringAnomalyScanEntity> findLastFinished(Pageable pageable);

    /**
     * Scans still occupying the single active slot.
     *
     * <p>Read alongside the advisory lock, not instead of it: this gives the caller
     * a scan number to report, while the lock is what actually prevents two.
     */
    @Query("SELECT s FROM EngineeringAnomalyScanEntity s WHERE s.status IN ('QUEUED', 'RUNNING')")
    List<EngineeringAnomalyScanEntity> findActive();

    /** Highest sequence issued today, for the next SCAN-YYYYMMDD-NNNN. */
    @Query(value = """
           SELECT COALESCE(MAX(CAST(SUBSTRING(scan_number FROM 15) AS INT)), 0)
             FROM engineering_anomaly_scan
            WHERE scan_number LIKE :prefix || '%'
           """, nativeQuery = true)
    int maxSequenceForPrefix(@Param("prefix") String prefix);
}
