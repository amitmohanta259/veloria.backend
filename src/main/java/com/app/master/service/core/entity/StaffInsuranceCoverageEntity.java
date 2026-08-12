package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "staff_insurance_coverage")
public class StaffInsuranceCoverageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long staffId;
    private String insuranceProvider;
    private String policyId;
    private String document;
}