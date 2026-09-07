package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.EInvoiceDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EInvoiceDocumentRepository extends JpaRepository<EInvoiceDocumentEntity, Long> {

    Optional<EInvoiceDocumentEntity> findBySalesInvoiceId(Long salesInvoiceId);

    List<EInvoiceDocumentEntity> findByOrganizationIdOrderByIdDesc(Long organizationId);
}
