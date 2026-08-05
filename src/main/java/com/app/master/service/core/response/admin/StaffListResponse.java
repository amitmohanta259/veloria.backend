package com.app.master.service.core.response.admin;

import com.app.master.service.core.enums.StaffDepartment;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
public class StaffListResponse {

    private UUID uuid;
    private String avatarUrl;
    private StaffDepartment department;
    private String designation;
    private String workEmail;
    private String phone;
    private LocalDate joiningDate;
    private LocalDate resignDate;
    private Boolean active;

    public StaffListResponse(UUID uuid, String avatarUrl, StaffDepartment department, String designation,
                             String workEmail, String phone, LocalDate joiningDate, LocalDate resignDate,
                             Boolean active) {
        this.uuid = uuid;
        this.avatarUrl = avatarUrl;
        this.department = department;
        this.designation = designation;
        this.workEmail = workEmail;
        this.phone = phone;
        this.joiningDate = joiningDate;
        this.resignDate = resignDate;
        this.active = active;
    }
}
