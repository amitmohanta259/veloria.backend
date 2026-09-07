package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstinVerificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GstinVerificationRepository extends JpaRepository<GstinVerificationEntity, Long> {

    Optional<GstinVerificationEntity> findByOrganizationIdAndGstin(Long organizationId, String gstin);

    List<GstinVerificationEntity> findByOrganizationIdOrderByIdDesc(Long organizationId);
}
