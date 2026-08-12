package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.RoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoleRepository extends JpaRepository<RoleEntity, Long> {
    Optional<RoleEntity> findByUuid(UUID uuid);
    List<RoleEntity> findAllByOrderByDepartmentAscCreatedDesc();
    List<RoleEntity> findByDepartmentOrderByCreatedDesc(String department);

    // Hierarchy resolution — most specific first
    Optional<RoleEntity> findByStaffUuid(UUID staffUuid);
    Optional<RoleEntity> findByDepartmentAndDesignationIgnoreCaseAndStaffUuidIsNull(String department, String designation);
    Optional<RoleEntity> findByDepartmentAndDesignationIsNullAndStaffUuidIsNull(String department);
}
