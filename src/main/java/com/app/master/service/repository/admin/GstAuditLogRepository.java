package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstAuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GstAuditLogRepository extends JpaRepository<GstAuditLogEntity, Long> {

    List<GstAuditLogEntity> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, Long entityId);

    @Query("""
        SELECT a FROM GstAuditLogEntity a
        WHERE (:entityType IS NULL OR a.entityType = :entityType)
          AND (:period IS NULL OR a.taxPeriod = :period)
        ORDER BY a.createdAt DESC
    """)
    Page<GstAuditLogEntity> findFiltered(@Param("entityType") String entityType,
                                         @Param("period") String period,
                                         Pageable pageable);
}
