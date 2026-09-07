package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "gst_state_master")
public class GstStateMasterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "state_code", unique = true, nullable = false, length = 2)
    private String stateCode;

    @Column(name = "state_name", nullable = false)
    private String stateName;

    @Column(name = "is_ut")
    private Boolean isUt;

    private Boolean active;
}
