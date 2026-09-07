package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.SalesInvoiceEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SalesInvoiceRepository extends JpaRepository<SalesInvoiceEntity, Long> {

    Optional<SalesInvoiceEntity> findByInvoiceNumber(String invoiceNumber);

    boolean existsByInvoiceNumber(String invoiceNumber);

    List<SalesInvoiceEntity> findByOrderIdOrderByIdAsc(Long orderId);

    /** The live (non-cancelled) invoice for an order, if one exists. */
    @Query("""
        SELECT i FROM SalesInvoiceEntity i
        WHERE i.orderId = :orderId AND i.status <> 'CANCELLED'
        ORDER BY i.id DESC
    """)
    List<SalesInvoiceEntity> findActiveForOrder(@Param("orderId") Long orderId);

    List<SalesInvoiceEntity> findByOrganizationIdAndTaxPeriodOrderByIdAsc(Long organizationId, String taxPeriod);

    List<SalesInvoiceEntity> findByOriginalInvoiceIdOrderByIdAsc(Long originalInvoiceId);

    @Query("""
        SELECT i FROM SalesInvoiceEntity i
        WHERE i.organizationId = :orgId
          AND (:period IS NULL OR i.taxPeriod = :period)
          AND (:status IS NULL OR i.status = :status)
          AND (:search IS NULL OR :search = ''
               OR LOWER(i.invoiceNumber) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(i.customerName) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(i.orderCode) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY i.id DESC
    """)
    Page<SalesInvoiceEntity> findFiltered(@Param("orgId") Long orgId,
                                          @Param("period") String period,
                                          @Param("status") String status,
                                          @Param("search") String search,
                                          Pageable pageable);

    /** Invoices raised against an order, newest first. */
    List<SalesInvoiceEntity> findByOrderCodeOrderByIdDesc(String orderCode);
}
