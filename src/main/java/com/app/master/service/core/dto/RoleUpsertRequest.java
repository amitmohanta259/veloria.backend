package com.app.master.service.core.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class RoleUpsertRequest {
    private String department;   // required
    private String designation;  // optional
    private UUID staffUuid;      // optional
    private List<PermissionRequest> permissions;

    @Data
    public static class PermissionRequest {
        private String module;
        private Boolean canView;
        private Boolean canCreate;
        private Boolean canEdit;
        private Boolean canDelete;
    }
}
