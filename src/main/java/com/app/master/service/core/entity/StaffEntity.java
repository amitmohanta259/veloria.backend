package com.app.master.service.core.entity;

import com.app.master.service.core.dto.Base;
import com.app.master.service.core.enums.StaffDepartment;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "staff")
public class StaffEntity extends Base {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    private String avatar;

    @Enumerated(EnumType.STRING)
    private StaffDepartment department;

    private String designation;
    private String workEmail;
    private String phone;
    private LocalDate joiningDate;
    private LocalDate resignDate;
}