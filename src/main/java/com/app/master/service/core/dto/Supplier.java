package com.app.master.service.core.dto;

import lombok.*;

import java.util.UUID;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Supplier {

    private UUID uuid;
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
}
