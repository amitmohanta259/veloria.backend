package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstInvoiceSeriesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GstInvoiceSeriesRepository extends JpaRepository<GstInvoiceSeriesEntity, Long> {

    Optional<GstInvoiceSeriesEntity> findByOrganizationIdAndGstRegistrationIdAndDocumentTypeAndFinancialYear(
            Long organizationId, Long gstRegistrationId, String documentType, String financialYear);

    List<GstInvoiceSeriesEntity> findByOrganizationIdOrderByIdAsc(Long organizationId);


}
