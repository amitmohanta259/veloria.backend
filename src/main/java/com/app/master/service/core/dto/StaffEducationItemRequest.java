package com.app.master.service.core.dto;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StaffEducationItemRequest {

    private String level;
    private String institute;
    private String city;
    private String year;
    private String percentage;
    /** Existing S3 URL when updating without re-uploading */
    private String certificateUrl;
}
