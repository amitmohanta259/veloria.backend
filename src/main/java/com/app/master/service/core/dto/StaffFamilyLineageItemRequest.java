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
    private String documentUrl;
}