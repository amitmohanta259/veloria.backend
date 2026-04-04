package com.app.master.service.core.dto;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StaffFamilyLineageItemRequest {

    private String fullName;
    private String relation;
    private String contactNumber;
    /** Existing S3 URL when updating without re-uploading */
    private String documentUrl;
}
