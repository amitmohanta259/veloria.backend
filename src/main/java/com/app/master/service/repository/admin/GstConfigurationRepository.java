package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstConfigurationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GstConfigurationRepository extends JpaRepository<GstConfigurationEntity, Long> {

    @Query("""
        SELECT c FROM GstConfigurationEntity c
        WHERE c.configKey = :key
          AND c.active = true
          AND (c.organizationId IS NULL OR c.organizationId = :orgId)
        ORDER BY c.effectiveFrom DESC, c.id DESC
    """)
    List<GstConfigurationEntity> findEffective(@Param("orgId") Long orgId, @Param("key") String key);

    List<GstConfigurationEntity> findByOrganizationIdAndActiveTrueOrderByConfigKeyAsc(Long organizationId);
}
