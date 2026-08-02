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
    private String certificateUrl;
}