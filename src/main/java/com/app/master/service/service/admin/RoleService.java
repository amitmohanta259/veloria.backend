package com.app.master.service.service.admin;

import com.app.master.service.core.dto.RoleUpsertRequest;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.admin.EffectiveRoleResponse;
import com.app.master.service.core.response.admin.RoleResponse;

import java.util.List;
import java.util.UUID;

public interface RoleService {
    RoleResponse createRole(RoleUpsertRequest request) throws VeloriaException;
    List<RoleResponse> getRoles(String department);
    RoleResponse getRoleByUuid(UUID uuid) throws VeloriaException;
    RoleResponse updateRole(UUID uuid, RoleUpsertRequest request) throws VeloriaException;
    void deleteRole(UUID uuid) throws VeloriaException;
    EffectiveRoleResponse getEffectivePermissions(UUID staffUuid) throws VeloriaException;
}
