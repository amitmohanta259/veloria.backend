package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.ItcReconciliationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ItcReconciliationRepository extends JpaRepository<ItcReconciliationEntity, Long> {

    List<ItcReconciliationEntity> findByOrganizationIdAndTaxPeriodOrderByIdAsc(Long organizationId, String taxPeriod);

    Optional<ItcReconciliationEntity> findByInputTaxId(Long inputTaxId);

    void deleteByOrganizationIdAndTaxPeriod(Long organizationId, String taxPeriod);
}
