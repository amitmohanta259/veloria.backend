package com.app.master.service.core.response.admin;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class SupplierListResponse {

    private UUID uuid;
    private String supplierCode;
    private String name;
    private String gstn;
    private String category;
    private String country;
    private String city;
    private String status;

    public SupplierListResponse(UUID uuid, String supplierCode, String name, String gstn,
                                String category, String country, String city, String status) {
        this.uuid = uuid;
        this.supplierCode = supplierCode;
        this.name = name;
        this.gstn = gstn;
        this.category = category;
        this.country = country;
        this.city = city;
        this.status = status;
    }
}
