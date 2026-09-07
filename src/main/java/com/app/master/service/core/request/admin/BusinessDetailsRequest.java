package com.app.master.service.core.request.admin;

import lombok.Data;

@Data
public class BusinessDetailsRequest {
    private String gstNumber;
    private String companyName;
    private String companyAddress;
    private String ownerName;
    private String registeredPhone;
    private String registeredEmail;
    private String sellerStateCode;
    private String pincode;
}
