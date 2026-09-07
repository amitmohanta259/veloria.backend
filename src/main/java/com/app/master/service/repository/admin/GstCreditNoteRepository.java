package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstCreditNoteEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GstCreditNoteRepository extends JpaRepository<GstCreditNoteEntity, Long> {

    /**
     * An order can carry several credit notes once partial and repeat returns
     * are supported, so this is a list rather than the single Optional the
     * one-return-per-order design assumed.
     */
    List<GstCreditNoteEntity> findByOriginalOrderCodeOrderByIdAsc(String orderCode);

    /** Idempotency key: one credit note per return event. */
    Optional<GstCreditNoteEntity> findByReturnRequestId(Long returnRequestId);

    boolean existsByOriginalOrderCode(String orderCode);

    List<GstCreditNoteEntity> findByTaxPeriodOrderByCreatedAtDesc(String taxPeriod);

    List<GstCreditNoteEntity> findByStatusOrderByCreatedAtDesc(String status);

    @Query("""
        SELECT c FROM GstCreditNoteEntity c
        WHERE c.organizationId = :orgId
          AND (:period IS NULL OR c.taxPeriod = :period)
          AND (:search IS NULL OR :search = ''
               OR LOWER(c.customerName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.originalOrderCode) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(c.creditNoteNumber) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY c.createdAt DESC
    """)
    Page<GstCreditNoteEntity> findFiltered(@Param("orgId") Long orgId,
                                           @Param("period") String period,
                                           @Param("search") String search,
                                           Pageable pageable);
}
