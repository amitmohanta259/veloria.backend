package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "gst_hsn_master")
public class GstHsnMasterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hsn_code", nullable = false)
    private String hsnCode;

    @Column(nullable = false)
    private String description;

    private String chapter;

    private Boolean active;
}
