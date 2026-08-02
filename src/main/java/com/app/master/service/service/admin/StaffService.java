package com.app.master.service.service.admin;

import com.app.master.service.core.dto.StaffUpsertRequest;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.admin.StaffDetailResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface StaffService {

    void createStaff(StaffUpsertRequest request, MultipartFile avatar, List<MultipartFile> educationFiles,
                     List<MultipartFile> familyLineageFiles, List<MultipartFile> legalVerificationFiles) throws VeloriaException;

    void updateStaff(UUID staffUuid, StaffUpsertRequest request, MultipartFile avatar, List<MultipartFile> educationFiles,
                     List<MultipartFile> familyLineageFiles, List<MultipartFile> legalVerificationFiles) throws VeloriaException;

    StaffDetailResponse getStaffByUuid(UUID staffUuid) throws VeloriaException;

}