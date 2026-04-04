package com.app.master.service.core.dto;

import com.app.master.service.core.enums.StaffDepartment;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StaffUpsertRequest {

    /** Used when no avatar file is sent */
    private String avatarUrl;

    private StaffDepartment department;
    private String designation;
    private String workEmail;
    private String phone;
    private LocalDate joiningDate;
    private LocalDate resignDate;

    private List<StaffEducationItemRequest> educationHistory;
    private List<StaffFamilyLineageItemRequest> familyLineage;
    private List<StaffInsuranceItemRequest> insuranceCoverage;
    private List<StaffLegalVerificationItemRequest> legalVerification;
    private List<StaffResidencyItemRequest> residency;
}
