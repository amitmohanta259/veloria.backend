package com.app.master.service.core.response.admin;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class BusinessDetailsResponse {
    private UUID uuid;
    private String gstNumber;
    private String companyName;
    private String companyAddress;
    private String ownerName;
    private String registeredPhone;
    private String registeredEmail;
    private String sellerStateCode;
    private String pincode;
}
