package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.EWayBillDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EWayBillDocumentRepository extends JpaRepository<EWayBillDocumentEntity, Long> {

    Optional<EWayBillDocumentEntity> findBySalesInvoiceId(Long salesInvoiceId);

    List<EWayBillDocumentEntity> findByOrganizationIdOrderByIdDesc(Long organizationId);
}
