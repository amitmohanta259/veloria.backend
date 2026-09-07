package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstReportExportEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GstReportExportRepository extends JpaRepository<GstReportExportEntity, Long> {

    List<GstReportExportEntity> findByOrganizationIdOrderByIdDesc(Long organizationId);
}
