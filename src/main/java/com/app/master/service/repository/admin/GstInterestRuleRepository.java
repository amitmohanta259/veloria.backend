package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstInterestRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GstInterestRuleRepository extends JpaRepository<GstInterestRuleEntity, Long> {

    List<GstInterestRuleEntity> findByOrganizationIdAndActiveTrueOrderByEffectiveFromDesc(
            Long organizationId);

    List<GstInterestRuleEntity> findByOrganizationIdAndRuleTypeAndActiveTrueOrderByEffectiveFromDesc(
            Long organizationId, String ruleType);
}
