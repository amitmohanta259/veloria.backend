package com.app.master.service.repository.testing;

import com.app.master.service.core.entity.DefectEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DefectRepository extends JpaRepository<DefectEntity, Long> {

    Optional<DefectEntity> findByUuid(UUID uuid);
    Optional<DefectEntity> findByDefectNumber(String defectNumber);
    Optional<DefectEntity> findByFingerprint(String fingerprint);

    /**
     * The register, filtered. {@code openOnly} is what the dashboard's default
     * list uses — a closed defect leaves that list but never the register.
     */
    @Query("""
           SELECT d FROM DefectEntity d
            WHERE (:status   IS NULL OR d.status   = :status)
              AND (:severity IS NULL OR d.severity = :severity)
              AND (:module   IS NULL OR d.module   = :module)
              AND (:openOnly = false OR d.status <> 'CLOSED')
            ORDER BY
              CASE d.severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1
                              WHEN 'MEDIUM' THEN 2 ELSE 3 END,
              d.openedAt DESC
           """)
    Page<DefectEntity> search(@Param("status") String status,
                              @Param("severity") String severity,
                              @Param("module") String module,
                              @Param("openOnly") boolean openOnly,
                              Pageable pageable);

    @Query("SELECT d.status, COUNT(d) FROM DefectEntity d GROUP BY d.status")
    List<Object[]> countByStatus();

    @Query("SELECT d.severity, COUNT(d) FROM DefectEntity d WHERE d.status <> 'CLOSED' GROUP BY d.severity")
    List<Object[]> countOpenBySeverity();

    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(defect_number FROM 5) AS INT)), 0) FROM engineering_defect",
           nativeQuery = true)
    int maxDefectSequence();
}
