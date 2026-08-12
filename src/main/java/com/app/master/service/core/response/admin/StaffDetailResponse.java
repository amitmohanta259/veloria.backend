package com.app.master.service.core.response.admin;

import com.app.master.service.core.enums.StaffDepartment;
import lombok.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffDetailResponse {

    private UUID uuid;
    private String avatarObjectKey;
    private String avatarPresignedUrl;
    private String name;
    private StaffDepartment department;
    private String designation;
    private String workEmail;
    private String phone;
    private LocalDate joiningDate;
    private LocalDate resignDate;
    private Boolean active;
    private Boolean archive;

    private List<EducationRow> educationHistory;
    private List<FamilyRow> familyLineage;
    private List<InsuranceRow> insuranceCoverage;
    private List<LegalRow> legalVerification;
    private List<ResidencyRow> residency;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EducationRow {
        private Long id;
        private String level;
        private String institute;
        private String city;
        private String year;
        private String percentage;
        private String certificateObjectKey;
        private String certificatePresignedUrl;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FamilyRow {
        private Long id;
        private String fullName;
        private String relation;
        private String contactNumber;
        private String documentObjectKey;
        private String documentPresignedUrl;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class InsuranceRow {
        private Long id;
        private String insuranceProvider;
        private String policyId;
        private String documentObjectKey;
        private String documentPresignedUrl;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LegalRow {
        private Long id;
        private String documentType;
        private String identificationNumber;
        private String documentObjectKey;
        private String documentPresignedUrl;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ResidencyRow {
        private String houseNo;
        private String lane;
        private String city;
        private String state;
        private String pin;
        private String type;
    }
}