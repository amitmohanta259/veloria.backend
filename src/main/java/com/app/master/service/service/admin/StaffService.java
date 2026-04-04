package com.app.master.service.service.admin;

import com.app.master.service.core.dto.StaffUpsertRequest;
import com.app.master.service.core.exception.VeloriaException;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface StaffService {

    Map<String, UUID> createStaff(StaffUpsertRequest request,
                                  MultipartFile avatar,
                                  List<MultipartFile> educationFiles,
                                  List<MultipartFile> familyLineageFiles,
                                  List<MultipartFile> legalVerificationFiles) throws VeloriaException;

    Map<String, UUID> updateStaff(UUID staffUuid,
                                  StaffUpsertRequest request,
                                  MultipartFile avatar,
                                  List<MultipartFile> educationFiles,
                                  List<MultipartFile> familyLineageFiles,
                                  List<MultipartFile> legalVerificationFiles) throws VeloriaException;
}
