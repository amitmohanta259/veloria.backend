package com.app.master.service.core.dto;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StaffLegalVerificationItemRequest {

    private String documentType;
    private String identificationNumber;
    private String documentUrl;
}