package com.app.master.service.core.response.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class EffectiveRoleResponse {
    private String appliedLevel;   // INDIVIDUAL | DESIGNATION | DEPARTMENT | GLOBAL
    private String scopeLabel;
    private List<RoleResponse.PermissionRow> permissions;
}
