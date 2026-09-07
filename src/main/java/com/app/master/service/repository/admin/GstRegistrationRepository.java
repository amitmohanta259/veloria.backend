package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstRegistrationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GstRegistrationRepository extends JpaRepository<GstRegistrationEntity, Long> {

    Optional<GstRegistrationEntity> findByGstin(String gstin);

    List<GstRegistrationEntity> findByOrganizationIdAndIsActiveTrueOrderByIdAsc(Long organizationId);

    Optional<GstRegistrationEntity> findFirstByOrganizationIdAndIsPrimaryTrue(Long organizationId);

    Optional<GstRegistrationEntity> findFirstByOrganizationIdAndIsActiveTrueOrderByIdAsc(Long organizationId);

    /** Registration covering a place of supply, for multi-state organizations. */
    Optional<GstRegistrationEntity> findFirstByOrganizationIdAndStateCodeAndIsActiveTrue(
            Long organizationId, String stateCode);
}
