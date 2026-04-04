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
    /** Existing S3 URL when updating without re-uploading */
    private String documentUrl;
}
