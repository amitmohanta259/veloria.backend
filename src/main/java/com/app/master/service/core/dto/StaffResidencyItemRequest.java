package com.app.master.service.core.dto;

import com.app.master.service.core.enums.StaffResidencyType;
import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StaffResidencyItemRequest {

    private String houseNo;
    private String lane;
    private String city;
    private String state;
    private String pin;
    private StaffResidencyType residencyType;
}
