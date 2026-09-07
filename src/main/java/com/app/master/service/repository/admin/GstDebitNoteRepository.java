package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstDebitNoteEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GstDebitNoteRepository extends JpaRepository<GstDebitNoteEntity, Long> {

    Optional<GstDebitNoteEntity> findByDebitNoteNumber(String debitNoteNumber);

    List<GstDebitNoteEntity> findByOrganizationIdAndTaxPeriodOrderByIdAsc(Long organizationId, String taxPeriod);

    @Query("""
        SELECT d FROM GstDebitNoteEntity d
        WHERE d.organizationId = :orgId
          AND (:period IS NULL OR d.taxPeriod = :period)
          AND (:search IS NULL OR :search = ''
               OR LOWER(d.customerName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(d.debitNoteNumber) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(d.originalOrderCode) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY d.id DESC
    """)
    Page<GstDebitNoteEntity> findFiltered(@Param("orgId") Long orgId,
                                          @Param("period") String period,
                                          @Param("search") String search,
                                          Pageable pageable);
}
