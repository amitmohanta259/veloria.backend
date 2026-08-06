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
@Table(name = "supplier")
public class SupplierEntity extends Base {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    private String supplierCode;
    private String name;
    private String registrationName;
    private String gstn;
    private String category;
    private String status;
    private String country;
    private String city;
    private String postalAddress;
    private String website;
    private String contactName;
    private String contactEmail;
    private String contactPhone;
    private String paymentTerms;
    private String settlementCurrency;
    private String bankName;
    private String swiftCode;
    private String accountNumber;
    private String productTags;
    private String leadTime;
    private String moq;
    private String notes;

    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Builder.Default
    private Boolean archive = Boolean.FALSE;
}
