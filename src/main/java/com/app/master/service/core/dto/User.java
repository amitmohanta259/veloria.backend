package com.app.master.service.core.dto;

import com.app.master.service.core.enums.Category;
import com.app.master.service.core.enums.Gender;
import com.app.master.service.core.enums.Role;
import com.app.master.service.core.enums.RoleType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    private String userName;

    private String iamId;

    private String firstName;

    private String lastName;

    private String middleName;

    private String email;

    private String phone;

    private String dob;

    @Enumerated(EnumType.STRING)
    private Category category;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private String avatar;

    private String fax;

    private Instant lastLogin;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Enumerated(EnumType.STRING)
    private RoleType roleType;

    public String userIdentifier;
    public boolean emailVerified;
    public String tenantKey;
    public String tenantGroup;

    private Boolean active;
    private Boolean archive;
}