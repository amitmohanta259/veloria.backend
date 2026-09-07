package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.PurchaseInvoiceEntity;
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
public interface PurchaseInvoiceRepository extends JpaRepository<PurchaseInvoiceEntity, Long> {

    Optional<PurchaseInvoiceEntity> findByOrganizationIdAndVendorGstinAndVendorInvoiceNumber(
            Long organizationId, String vendorGstin, String vendorInvoiceNumber);

    boolean existsByOrganizationIdAndVendorGstinAndVendorInvoiceNumber(
            Long organizationId, String vendorGstin, String vendorInvoiceNumber);

    List<PurchaseInvoiceEntity> findByOrganizationIdAndTaxPeriodOrderByIdAsc(Long organizationId, String taxPeriod);

    List<PurchaseInvoiceEntity> findByPurchaseOrderIdOrderByIdAsc(Long purchaseOrderId);

    @Query("""
        SELECT i FROM PurchaseInvoiceEntity i
        WHERE i.organizationId = :orgId
          AND (:period IS NULL OR i.taxPeriod = :period)
          AND (:search IS NULL OR :search = ''
               OR LOWER(i.vendorInvoiceNumber) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(i.vendorName) LIKE LOWER(CONCAT('%', :search, '%')))
        ORDER BY i.id DESC
    """)
    Page<PurchaseInvoiceEntity> findFiltered(@Param("orgId") Long orgId,
                                             @Param("period") String period,
                                             @Param("search") String search,
                                             Pageable pageable);
}
