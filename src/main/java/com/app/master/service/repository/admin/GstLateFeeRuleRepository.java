package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstLateFeeRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GstLateFeeRuleRepository extends JpaRepository<GstLateFeeRuleEntity, Long> {

    List<GstLateFeeRuleEntity> findByOrganizationIdAndActiveTrueOrderByEffectiveFromDesc(
            Long organizationId);

    List<GstLateFeeRuleEntity> findByOrganizationIdAndReturnTypeAndActiveTrueOrderByEffectiveFromDesc(
            Long organizationId, String returnType);
}
