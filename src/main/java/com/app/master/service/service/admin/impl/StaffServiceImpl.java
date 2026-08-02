package com.app.master.service.service.admin.impl;

import com.app.master.service.core.dto.*;
import com.app.master.service.core.entity.*;
import com.app.master.service.core.enums.StaffResidencyType;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.admin.StaffDetailResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.core.service.AwsService;
import com.app.master.service.repository.admin.*;
import com.app.master.service.service.admin.StaffService;
import com.google.common.base.Strings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class StaffServiceImpl extends AppService implements StaffService {

    private final StaffRepository staffRepository;
    private final StaffEducationHistoryRepository educationHistoryRepository;
    private final StaffFamilyLineageRepository familyLineageRepository;
    private final StaffInsuranceCoverageRepository insuranceCoverageRepository;
    private final StaffLegalVerificationRepository legalVerificationRepository;
    private final StaffResidencyRepository residencyRepository;
    private final AwsService awsService;

    public StaffServiceImpl(StaffRepository staffRepository, StaffEducationHistoryRepository educationHistoryRepository, StaffFamilyLineageRepository familyLineageRepository,
                            StaffInsuranceCoverageRepository insuranceCoverageRepository, StaffLegalVerificationRepository legalVerificationRepository, StaffResidencyRepository residencyRepository, AwsService awsService) {
        this.staffRepository = staffRepository;
        this.educationHistoryRepository = educationHistoryRepository;
        this.familyLineageRepository = familyLineageRepository;
        this.insuranceCoverageRepository = insuranceCoverageRepository;
        this.legalVerificationRepository = legalVerificationRepository;
        this.residencyRepository = residencyRepository;
        this.awsService = awsService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createStaff(StaffUpsertRequest request, MultipartFile avatar, List<MultipartFile> educationFiles,
                            List<MultipartFile> familyLineageFiles, List<MultipartFile> legalVerificationFiles) throws VeloriaException {
        validateResidency(request.getResidency());

        StaffEntity staff = StaffEntity.builder()
                .department(request.getDepartment())
                .designation(request.getDesignation())
                .workEmail(request.getWorkEmail())
                .phone(request.getPhone())
                .joiningDate(request.getJoiningDate())
                .resignDate(request.getResignDate())
                .build();

        applyAvatar(staff, request, avatar, true);
        staff = staffRepository.save(staff);

        replaceRelatedRecords(staff, request, educationFiles, familyLineageFiles, legalVerificationFiles, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStaff(UUID staffUuid, StaffUpsertRequest request, MultipartFile avatar, List<MultipartFile> educationFiles,
                            List<MultipartFile> familyLineageFiles, List<MultipartFile> legalVerificationFiles) throws VeloriaException {
        validateResidency(request.getResidency());

        StaffEntity staff = staffRepository.findByUuid(staffUuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid staff uuid"));

        staff.setDepartment(request.getDepartment());
        staff.setDesignation(request.getDesignation());
        staff.setWorkEmail(request.getWorkEmail());
        staff.setPhone(request.getPhone());
        staff.setJoiningDate(request.getJoiningDate());
        staff.setResignDate(request.getResignDate());

        String previousAvatarKey = staff.getAvatar();
        applyAvatar(staff, request, avatar, false);
        staff = staffRepository.save(staff);
        deleteStaleS3Object(previousAvatarKey, staff.getAvatar());

        replaceRelatedRecords(staff, request, educationFiles, familyLineageFiles, legalVerificationFiles, true);
    }

    @Override
    @Transactional(readOnly = true)
    public StaffDetailResponse getStaffByUuid(UUID staffUuid) throws VeloriaException {

        StaffEntity staff = staffRepository.findByUuid(staffUuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid staff uuid"));
        Long staffId = staff.getId();

        List<StaffDetailResponse.EducationRow> educationRows = new ArrayList<>();
        for (StaffEducationHistoryEntity e : educationHistoryRepository.findByStaffId(staffId)) {
            String certKey = e.getCertificate();
            String certPresigned = null;
            if (!Strings.isNullOrEmpty(certKey)) {
                try {
                    certPresigned = awsService.getViewablePreSignedUrl(certKey);
                } catch (Exception ignored) {
                }
            }
            educationRows.add(StaffDetailResponse.EducationRow.builder()
                    .level(e.getLevel())
                    .institute(e.getInstitute())
                    .city(e.getCity())
                    .year(e.getYear())
                    .percentage(e.getPercentage())
                    .certificateObjectKey(certKey)
                    .certificatePresignedUrl(certPresigned)
                    .build());
        }

        List<StaffDetailResponse.FamilyRow> familyRows = new ArrayList<>();
        for (StaffFamilyLineageEntity e : familyLineageRepository.findByStaffId(staffId)) {
            String doc = e.getDocument();
            String docPresigned = null;
            if (!Strings.isNullOrEmpty(doc)) {
                try {
                    docPresigned = awsService.getViewablePreSignedUrl(doc);
                } catch (Exception ignored) {
                }
            }
            familyRows.add(StaffDetailResponse.FamilyRow.builder()
                    .fullName(e.getFullName())
                    .relation(e.getRelation())
                    .contactNumber(e.getContactNumber())
                    .documentObjectKey(doc)
                    .documentPresignedUrl(docPresigned)
                    .build());
        }

        List<StaffDetailResponse.InsuranceRow> insuranceRows = new ArrayList<>();
        for (StaffInsuranceCoverageEntity e : insuranceCoverageRepository.findByStaffId(staffId)) {
            insuranceRows.add(StaffDetailResponse.InsuranceRow.builder()
                    .insuranceProvider(e.getInsuranceProvider())
                    .policyId(e.getPolicyId())
                    .build());
        }

        List<StaffDetailResponse.LegalRow> legalRows = new ArrayList<>();
        for (StaffLegalVerificationEntity e : legalVerificationRepository.findByStaffId(staffId)) {
            String doc = e.getDocument();
            String docPresigned = null;
            if (!Strings.isNullOrEmpty(doc)) {
                try {
                    docPresigned = awsService.getViewablePreSignedUrl(doc);
                } catch (Exception ignored) {
                }
            }
            legalRows.add(StaffDetailResponse.LegalRow.builder()
                    .documentType(e.getDocumentType())
                    .identificationNumber(e.getIdentificationNumber())
                    .documentObjectKey(doc)
                    .documentPresignedUrl(docPresigned)
                    .build());
        }

        List<StaffDetailResponse.ResidencyRow> residencyRows = new ArrayList<>();
        for (StaffResidencyEntity e : residencyRepository.findByStaffId(staffId)) {
            residencyRows.add(StaffDetailResponse.ResidencyRow.builder()
                    .houseNo(e.getHouseNo())
                    .lane(e.getLane())
                    .city(e.getCity())
                    .state(e.getState())
                    .pin(e.getPin())
                    .type(e.getType())
                    .build());
        }

        String avatarKey = staff.getAvatar();
        String avatarPresigned = null;
        if (!Strings.isNullOrEmpty(avatarKey)) {
            try {
                avatarPresigned = awsService.getViewablePreSignedUrl(avatarKey);
            } catch (Exception ignored) {
            }
        }

        return StaffDetailResponse.builder()
                .uuid(staff.getUuid())
                .avatarObjectKey(avatarKey)
                .avatarPresignedUrl(avatarPresigned)
                .department(staff.getDepartment())
                .designation(staff.getDesignation())
                .workEmail(staff.getWorkEmail())
                .phone(staff.getPhone())
                .joiningDate(staff.getJoiningDate())
                .resignDate(staff.getResignDate())
                .active(staff.getActive())
                .archive(staff.getArchive())
                .educationHistory(educationRows)
                .familyLineage(familyRows)
                .insuranceCoverage(insuranceRows)
                .legalVerification(legalRows)
                .residency(residencyRows)
                .build();
    }

    private void replaceRelatedRecords(StaffEntity staff, StaffUpsertRequest request, List<MultipartFile> educationFiles,
                                       List<MultipartFile> familyLineageFiles, List<MultipartFile> legalVerificationFiles,
                                       boolean isUpdate) throws VeloriaException {
        Long staffId = staff.getId();
        UUID staffUuid = staff.getUuid();

        Set<String> previousDocumentKeys = new HashSet<>();
        if (isUpdate) {
            educationHistoryRepository.findByStaffId(staffId)
                    .forEach(e -> addKeyIfPresent(previousDocumentKeys, e.getCertificate()));
            familyLineageRepository.findByStaffId(staffId)
                    .forEach(e -> addKeyIfPresent(previousDocumentKeys, e.getDocument()));
            legalVerificationRepository.findByStaffId(staffId)
                    .forEach(e -> addKeyIfPresent(previousDocumentKeys, e.getDocument()));
        }

        educationHistoryRepository.deleteByStaffId(staffId);
        familyLineageRepository.deleteByStaffId(staffId);
        insuranceCoverageRepository.deleteByStaffId(staffId);
        legalVerificationRepository.deleteByStaffId(staffId);
        residencyRepository.deleteByStaffId(staffId);

        Set<String> retainedDocumentKeys = new HashSet<>();
        saveEducation(staffId, staffUuid, orEmpty(request.getEducationHistory()), educationFiles, retainedDocumentKeys);
        saveFamilyLineage(staffId, staffUuid, orEmpty(request.getFamilyLineage()), familyLineageFiles, retainedDocumentKeys);
        saveInsurance(staffId, orEmpty(request.getInsuranceCoverage()));
        saveLegal(staffId, staffUuid, orEmpty(request.getLegalVerification()), legalVerificationFiles, retainedDocumentKeys);
        saveResidency(staffId, request.getResidency());

        if (isUpdate) {
            for (String key : previousDocumentKeys) {
                if (!retainedDocumentKeys.contains(key)) {
                    try {
                        awsService.deleteKey(key);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private static void addKeyIfPresent(Set<String> keys, String key) {
        if (!Strings.isNullOrEmpty(key)) {
            keys.add(key);
        }
    }

    private void deleteStaleS3Object(String previousKey, String newKey) {
        if (Strings.isNullOrEmpty(previousKey) || previousKey.equals(newKey)) {
            return;
        }
        try {
            awsService.deleteKey(previousKey);
        } catch (Exception ignored) {
        }
    }

    private void validateResidency(List<StaffResidencyItemRequest> residency) throws VeloriaException {
        if (residency == null || residency.size() != 2) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "Residency must contain exactly two entries: one PERMANENT and one CURRENT");
        }
        boolean permanent = false;
        boolean current = false;
        for (StaffResidencyItemRequest row : residency) {
            if (row.getResidencyType() == null) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST, "residencyType is required on each residency row");
            }
            if (row.getResidencyType() == StaffResidencyType.PERMANENT) {
                permanent = true;
            }
            if (row.getResidencyType() == StaffResidencyType.CURRENT) {
                current = true;
            }
        }
        if (!permanent || !current) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "Residency must include exactly one PERMANENT and one CURRENT address");
        }
    }

    private void applyAvatar(StaffEntity staff, StaffUpsertRequest request, MultipartFile avatar, boolean isCreate)
            throws VeloriaException {
        if (avatar != null && !avatar.isEmpty()) {
            try {
                String path = awsService.getStaffAvatarPath(staff.getUuid(), avatar.getOriginalFilename());
                staff.setAvatar(awsService.uploadDocumentMultipart(avatar, path));
            } catch (IOException e) {
                throw new VeloriaException(ResponseCode.AWS_ERROR, "Avatar upload failed: " + e.getMessage());
            }
        } else if (!Strings.isNullOrEmpty(request.getAvatarUrl())) {
            staff.setAvatar(request.getAvatarUrl());
        } else if (isCreate) {
            staff.setAvatar(null);
        }
    }

    private void saveEducation(Long staffId, UUID staffUuid, List<StaffEducationItemRequest> items, List<MultipartFile> files,
                               Set<String> retainedDocumentKeys) throws VeloriaException {
        if (items.isEmpty()) {
            return;
        }
        List<StaffEducationHistoryEntity> entities = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            StaffEducationItemRequest item = items.get(i);
            String certificate = resolveUploadedOrExisting(
                    item.getCertificateUrl(), fileAt(files, i), staffUuid, "education");
            addKeyIfPresent(retainedDocumentKeys, certificate);
            entities.add(StaffEducationHistoryEntity.builder()
                    .staffId(staffId)
                    .level(item.getLevel())
                    .institute(item.getInstitute())
                    .city(item.getCity())
                    .year(item.getYear())
                    .percentage(item.getPercentage())
                    .certificate(certificate)
                    .build());
        }
        educationHistoryRepository.saveAll(entities);
    }

    private void saveFamilyLineage(Long staffId, UUID staffUuid, List<StaffFamilyLineageItemRequest> items, List<MultipartFile> files,
                                   Set<String> retainedDocumentKeys) throws VeloriaException {
        if (items.isEmpty()) {
            return;
        }
        List<StaffFamilyLineageEntity> entities = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            StaffFamilyLineageItemRequest item = items.get(i);
            String document = resolveUploadedOrExisting(
                    item.getDocumentUrl(), fileAt(files, i), staffUuid, "family");
            addKeyIfPresent(retainedDocumentKeys, document);
            entities.add(StaffFamilyLineageEntity.builder()
                    .staffId(staffId)
                    .fullName(item.getFullName())
                    .relation(item.getRelation())
                    .contactNumber(item.getContactNumber())
                    .document(document)
                    .build());
        }
        familyLineageRepository.saveAll(entities);
    }

    private void saveInsurance(Long staffId, List<StaffInsuranceItemRequest> items) {
        if (items.isEmpty()) {
            return;
        }
        List<StaffInsuranceCoverageEntity> entities = new ArrayList<>();
        for (StaffInsuranceItemRequest item : items) {
            entities.add(StaffInsuranceCoverageEntity.builder()
                    .staffId(staffId)
                    .insuranceProvider(item.getInsuranceProvider())
                    .policyId(item.getPolicyId())
                    .build());
        }
        insuranceCoverageRepository.saveAll(entities);
    }

    private void saveLegal(Long staffId, UUID staffUuid, List<StaffLegalVerificationItemRequest> items, List<MultipartFile> files,
                           Set<String> retainedDocumentKeys) throws VeloriaException {
        if (items.isEmpty()) {
            return;
        }
        List<StaffLegalVerificationEntity> entities = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            StaffLegalVerificationItemRequest item = items.get(i);
            String document = resolveUploadedOrExisting(item.getDocumentUrl(), fileAt(files, i), staffUuid, "legal");
            addKeyIfPresent(retainedDocumentKeys, document);
            entities.add(StaffLegalVerificationEntity.builder()
                    .staffId(staffId)
                    .documentType(item.getDocumentType())
                    .identificationNumber(item.getIdentificationNumber())
                    .document(document)
                    .build());
        }
        legalVerificationRepository.saveAll(entities);
    }

    private void saveResidency(Long staffId, List<StaffResidencyItemRequest> items) {
        List<StaffResidencyEntity> entities = new ArrayList<>();
        for (StaffResidencyItemRequest item : items) {
            entities.add(StaffResidencyEntity.builder()
                    .staffId(staffId)
                    .houseNo(item.getHouseNo())
                    .lane(item.getLane())
                    .city(item.getCity())
                    .state(item.getState())
                    .pin(item.getPin())
                    .type(item.getResidencyType().name())
                    .build());
        }
        residencyRepository.saveAll(entities);
    }

    private String resolveUploadedOrExisting(String existingKey, MultipartFile file, UUID staffUuid, String segment) throws VeloriaException {
        if (file != null && !file.isEmpty()) {
            try {
                String path = switch (segment) {
                    case "education" -> awsService.getStaffEducationDocumentPath(staffUuid, file.getOriginalFilename());
                    case "family" -> awsService.getStaffFamilyDocumentPath(staffUuid, file.getOriginalFilename());
                    case "legal" -> awsService.getStaffLegalDocumentPath(staffUuid, file.getOriginalFilename());
                    default ->
                            throw new VeloriaException(ResponseCode.BAD_REQUEST, "Unknown document segment: " + segment);
                };
                return awsService.uploadDocumentMultipart(file, path);
            } catch (IOException e) {
                throw new VeloriaException(ResponseCode.AWS_ERROR, "Document upload failed: " + e.getMessage());
            }
        }
        return Strings.isNullOrEmpty(existingKey) ? null : existingKey;
    }

    private MultipartFile fileAt(List<MultipartFile> files, int index) {
        if (files == null || index >= files.size()) {
            return null;
        }
        MultipartFile f = files.get(index);
        if (f == null || f.isEmpty()) {
            return null;
        }
        return f;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

}