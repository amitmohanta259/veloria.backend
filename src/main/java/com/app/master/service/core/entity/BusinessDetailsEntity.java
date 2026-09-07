package com.app.master.service.core.entity;

import com.app.master.service.core.dto.Base;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "business_details")
public class BusinessDetailsEntity extends Base {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    private String gstNumber;
    private String companyName;

    @Column(columnDefinition = "TEXT")
    private String companyAddress;

    private String ownerName;
    private String registeredPhone;
    private String registeredEmail;
    private String sellerStateCode;
    private String pincode;

    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Builder.Default
    private Boolean archive = Boolean.FALSE;
}
