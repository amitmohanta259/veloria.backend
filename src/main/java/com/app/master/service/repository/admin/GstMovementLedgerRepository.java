package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstMovementLedgerEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GstMovementLedgerRepository extends JpaRepository<GstMovementLedgerEntity, Long> {

    boolean existsBySourceTypeAndSourceId(String sourceType, Long sourceId);

    Optional<GstMovementLedgerEntity> findBySourceTypeAndSourceId(String sourceType, Long sourceId);

    List<GstMovementLedgerEntity> findByTaxPeriodOrderByCreatedAtDesc(String taxPeriod);

    List<GstMovementLedgerEntity> findByReferenceCreditNoteIdOrderByIdAsc(Long creditNoteId);

    List<GstMovementLedgerEntity> findByOrderItemIdOrderByIdAsc(Long orderItemId);

    List<GstMovementLedgerEntity> findBySourceTypeAndStatus(String sourceType, String status);

    /**
     * Superseded and cancelled rows are retained for audit but excluded from the
     * default view, so the tracker does not appear to double-count the
     * order-level movements that item-level ones replaced. Pass
     * includeHistorical = true to see them.
     */
    @Query("""
        SELECT g FROM GstMovementLedgerEntity g
        WHERE g.organizationId = :orgId
          AND (:period IS NULL OR g.taxPeriod = :period)
          AND (:movementType IS NULL OR g.movementType = :movementType)
          AND (:direction IS NULL OR g.direction = :direction)
          AND (:includeHistorical = true OR g.status NOT IN ('SUPERSEDED', 'CANCELLED'))
          AND (:search IS NULL OR :search = ''
               OR LOWER(g.counterpartyName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(g.sourceDocumentNumber) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(g.movementNumber) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(g.hsnCode) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY g.createdAt DESC
    """)
    Page<GstMovementLedgerEntity> findFiltered(
            @Param("orgId") Long orgId,
            @Param("period") String period,
            @Param("movementType") String movementType,
            @Param("direction") String direction,
            @Param("search") String search,
            @Param("includeHistorical") boolean includeHistorical,
            Pageable pageable);

    @Query(nativeQuery = true, value = """
        SELECT COALESCE(SUM(cgst_amount_paise),0), COALESCE(SUM(sgst_amount_paise),0),
               COALESCE(SUM(igst_amount_paise),0), COALESCE(SUM(total_tax_paise),0),
               COALESCE(SUM(taxable_value_paise),0)
        FROM gst_movement_ledger
        WHERE organization_id = :orgId
          AND direction = :direction
          AND (:period IS NULL OR tax_period = :period)
          AND status = 'POSTED'
    """)
    List<Object[]> sumByDirectionAndPeriod(@Param("orgId") Long orgId,
                                           @Param("direction") String direction,
                                           @Param("period") String period);

    @Query(nativeQuery = true, value = """
        SELECT COALESCE(SUM(cgst_amount_paise),0), COALESCE(SUM(sgst_amount_paise),0),
               COALESCE(SUM(igst_amount_paise),0), COALESCE(SUM(total_tax_paise),0),
               COALESCE(SUM(taxable_value_paise),0)
        FROM gst_movement_ledger
        WHERE organization_id = :orgId
          AND movement_type = :movementType
          AND (:period IS NULL OR tax_period = :period)
          AND status = 'POSTED'
    """)
    List<Object[]> sumByMovementTypeAndPeriod(@Param("orgId") Long orgId,
                                              @Param("movementType") String movementType,
                                              @Param("period") String period);
}
