package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "staff_residency")
public class StaffResidencyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long staffId;
    private String houseNo;
    private String lane;
    private String city;
    private String state;
    private String pin;
    private String type;
}