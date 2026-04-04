package com.app.master.service.core.dto;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StaffInsuranceItemRequest {

    private String insuranceProvider;
    private String policyId;
}
