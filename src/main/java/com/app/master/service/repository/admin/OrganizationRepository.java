package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.OrganizationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrganizationRepository extends JpaRepository<OrganizationEntity, Long> {
    Optional<OrganizationEntity> findByCode(String code);
    List<OrganizationEntity> findByActiveTrueOrderByIdAsc();
    Optional<OrganizationEntity> findFirstByActiveTrueOrderByIdAsc();
}
