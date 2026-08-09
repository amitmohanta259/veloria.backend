package com.app.master.service.service.admin;

import com.app.master.service.core.dto.StaffUpsertRequest;
import com.app.master.service.core.enums.StaffDepartment;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.admin.StaffDetailResponse;
import com.app.master.service.core.response.admin.StaffListResponse;
import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface StaffService {

    Map<String, UUID> createStaff(StaffUpsertRequest request, MultipartFile avatar,
                                   List<MultipartFile> educationFiles, List<MultipartFile> familyLineageFiles,
                                   List<MultipartFile> legalVerificationFiles) throws VeloriaException;

    Map<String, UUID> updateStaff(UUID staffUuid, StaffUpsertRequest request, MultipartFile avatar,
                                   List<MultipartFile> educationFiles, List<MultipartFile> familyLineageFiles,
                                   List<MultipartFile> legalVerificationFiles) throws VeloriaException;

    Page<StaffListResponse> getStaffList(int page, int pageSize, String search,
                                          StaffDepartment department, Boolean active) throws VeloriaException;

    StaffDetailResponse getStaffByUuid(UUID staffUuid) throws VeloriaException;

    boolean toggleStaffActive(UUID staffUuid) throws VeloriaException;

    void archiveStaff(UUID staffUuid) throws VeloriaException;

    Map<String, Long> getStaffStats();
}
