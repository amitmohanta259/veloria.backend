package com.app.master.service.core.entity;

import com.app.master.service.core.dto.Base;
import com.app.master.service.core.enums.Category;
import com.app.master.service.core.enums.Gender;
import com.app.master.service.core.enums.Role;
import com.app.master.service.core.enums.RoleType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Service
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "users")
public class UserEntity extends Base {

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

    private Boolean active;
    private Boolean archive;
}