package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.Gstr2bImportEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface Gstr2bImportRepository extends JpaRepository<Gstr2bImportEntity, Long> {

    List<Gstr2bImportEntity> findByOrganizationIdAndTaxPeriodOrderByIdDesc(Long organizationId, String taxPeriod);

    List<Gstr2bImportEntity> findByOrganizationIdOrderByIdDesc(Long organizationId);
}
