package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "role_permissions")
public class RolePermissionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long roleId;
    private String module;

    @Builder.Default private Boolean canView   = false;
    @Builder.Default private Boolean canCreate = false;
    @Builder.Default private Boolean canEdit   = false;
    @Builder.Default private Boolean canDelete = false;
}
