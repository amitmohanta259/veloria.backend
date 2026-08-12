package com.app.master.service.core.response.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class RoleResponse {
    private UUID uuid;
    private String department;
    private String designation;
    private UUID staffUuid;
    private String staffName;    // resolved from staffUuid if present
    private String scopeLabel;   // human-readable label e.g. "Engineering · Senior Engineer"
    private long memberCount;
    private String createdAt;
    private List<PermissionRow> permissions;

    @Data
    @Builder
    public static class PermissionRow {
        private String module;
        private Boolean canView;
        private Boolean canCreate;
        private Boolean canEdit;
        private Boolean canDelete;
    }
}
