package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstAccountingExceptionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GstAccountingExceptionRepository extends JpaRepository<GstAccountingExceptionEntity, Long> {

    List<GstAccountingExceptionEntity> findByStatusOrderByCreatedAtDesc(String status);

    long countByStatus(String status);

    @Query("""
        SELECT e FROM GstAccountingExceptionEntity e
        WHERE (:status IS NULL OR e.status = :status)
        ORDER BY e.createdAt DESC
    """)
    Page<GstAccountingExceptionEntity> findFiltered(@Param("status") String status, Pageable pageable);

    /** Detected exceptions are keyed by what they are about, so a rescan updates. */
    Optional<GstAccountingExceptionEntity> findByOrganizationIdAndExceptionTypeAndSourceDocumentNumber(
            Long organizationId, String exceptionType, String sourceDocumentNumber);
}
