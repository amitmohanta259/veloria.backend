package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.RolePermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermissionEntity, Long> {
    List<RolePermissionEntity> findByRoleId(Long roleId);
    void deleteByRoleId(Long roleId);
}
