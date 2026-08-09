package com.app.master.service.service.admin.impl;

import com.app.master.service.core.dto.*;
import com.app.master.service.core.entity.*;
import com.app.master.service.core.enums.StaffDepartment;
import com.app.master.service.core.enums.StaffResidencyType;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.admin.StaffDetailResponse;
import com.app.master.service.core.response.admin.StaffListResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.core.service.AwsService;
import com.app.master.service.repository.admin.*;
import com.app.master.service.service.admin.StaffService;
import com.google.common.base.Strings;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
public class StaffServiceImpl extends AppService implements StaffService {

    private final StaffRepository staffRepository;
    private final StaffEducationHistoryRepository educationHistoryRepository;
    private final StaffFamilyLineageRepository familyLineageRepository;
    private final StaffInsuranceCoverageRepository insuranceCoverageRepository;
    private final StaffLegalVerificationRepository legalVerificationRepository;
    private final StaffResidencyRepository residencyRepository;
    private final AwsService awsService;
    private final Executor taskExecutor;

    public StaffServiceImpl(StaffRepository staffRepository,
                            StaffEducationHistoryRepository educationHistoryRepository,
                            StaffFamilyLineageRepository familyLineageRepository,
                            StaffInsuranceCoverageRepository insuranceCoverageRepository,
                            StaffLegalVerificationRepository legalVerificationRepository,
                            StaffResidencyRepository residencyRepository,
                            AwsService awsService,
                            @Qualifier("taskExecutor") Executor taskExecutor) {
        this.staffRepository = staffRepository;
        this.educationHistoryRepository = educationHistoryRepository;
        this.familyLineageRepository = familyLineageRepository;
        this.insuranceCoverageRepository = insuranceCoverageRepository;
        this.legalVerificationRepository = legalVerificationRepository;
        this.residencyRepository = residencyRepository;
        this.awsService = awsService;
        this.taskExecutor = taskExecutor;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, UUID> createStaff(StaffUpsertRequest request, MultipartFile avatar,
                                          List<MultipartFile> educationFiles, List<MultipartFile> familyLineageFiles,
                                          List<MultipartFile> legalVerificationFiles) throws VeloriaException {
        validateResidency(request.getResidency());

        StaffEntity staff = StaffEntity.builder()
                .name(request.getName())
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

        return Map.of("uuid", staff.getUuid());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, UUID> updateStaff(UUID staffUuid, StaffUpsertRequest request, MultipartFile avatar,
                                          List<MultipartFile> educationFiles, List<MultipartFile> familyLineageFiles,
                                          List<MultipartFile> legalVerificationFiles) throws VeloriaException {
        validateResidency(request.getResidency());

        StaffEntity staff = staffRepository.findByUuid(staffUuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid staff uuid"));

        staff.setName(request.getName());
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

        return Map.of("uuid", staff.getUuid());
    }

    @Override
    public Page<StaffListResponse> getStaffList(int page, int pageSize, String search,
                                                 StaffDepartment department, Boolean active) throws VeloriaException {
        String normalizedSearch = Strings.isNullOrEmpty(search) ? null : search.toLowerCase();
        Pageable pageable = PageRequest.of(page, pageSize);
        Page<StaffListResponse> staffPage = staffRepository.getStaffList(normalizedSearch, department, active, pageable);
        presignAvatars(staffPage.getContent());
        return staffPage;
    }

    @Override
    public StaffDetailResponse getStaffByUuid(UUID staffUuid) throws VeloriaException {
        StaffEntity staff = staffRepository.findByUuid(staffUuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid staff uuid"));

        List<StaffEducationHistoryEntity> education = educationHistoryRepository.findByStaffId(staff.getId());
        List<StaffFamilyLineageEntity> family = familyLineageRepository.findByStaffId(staff.getId());
        List<StaffInsuranceCoverageEntity> insurance = insuranceCoverageRepository.findByStaffId(staff.getId());
        List<StaffLegalVerificationEntity> legal = legalVerificationRepository.findByStaffId(staff.getId());
        List<StaffResidencyEntity> residency = residencyRepository.findByStaffId(staff.getId());

        List<String> keysToPresign = collectDocumentKeys(staff, education, family, legal);
        Map<String, String> presignedUrls = presignAllKeys(keysToPresign);

        return buildDetailResponse(staff, education, family, insurance, legal, residency, presignedUrls);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleStaffActive(UUID staffUuid) throws VeloriaException {
        StaffEntity staff = staffRepository.findByUuid(staffUuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid staff uuid"));
        boolean newStatus = !staff.getActive();
        staff.setActive(newStatus);
        staffRepository.save(staff);
        return newStatus;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archiveStaff(UUID staffUuid) throws VeloriaException {
        StaffEntity staff = staffRepository.findByUuid(staffUuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid staff uuid"));
        staff.setArchive(true);
        staffRepository.save(staff);
    }

    // ---- Private helpers ----

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

    private void replaceRelatedRecords(StaffEntity staff, StaffUpsertRequest request,
                                        List<MultipartFile> educationFiles, List<MultipartFile> familyLineageFiles,
                                        List<MultipartFile> legalVerificationFiles, boolean isUpdate) throws VeloriaException {
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
                    try { awsService.deleteKey(key); } catch (Exception ignored) {}
                }
            }
        }
    }

    private void presignAvatars(List<StaffListResponse> items) {
        if (items.isEmpty()) return;
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (StaffListResponse item : items) {
            String key = item.getAvatarUrl();
            if (!Strings.isNullOrEmpty(key)) {
                tasks.add(CompletableFuture.runAsync(() -> {
                    try { item.setAvatarUrl(awsService.getViewablePreSignedUrl(key)); } catch (Exception ignored) {}
                }, taskExecutor));
            }
        }
        if (!tasks.isEmpty()) CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
    }

    private List<String> collectDocumentKeys(StaffEntity staff, List<StaffEducationHistoryEntity> education,
                                              List<StaffFamilyLineageEntity> family,
                                              List<StaffLegalVerificationEntity> legal) {
        List<String> keys = new ArrayList<>();
        if (!Strings.isNullOrEmpty(staff.getAvatar())) keys.add(staff.getAvatar());
        education.stream().map(StaffEducationHistoryEntity::getCertificate)
                .filter(k -> !Strings.isNullOrEmpty(k)).forEach(keys::add);
        family.stream().map(StaffFamilyLineageEntity::getDocument)
                .filter(k -> !Strings.isNullOrEmpty(k)).forEach(keys::add);
        legal.stream().map(StaffLegalVerificationEntity::getDocument)
                .filter(k -> !Strings.isNullOrEmpty(k)).forEach(keys::add);
        return keys;
    }

    private Map<String, String> presignAllKeys(List<String> keys) {
        Map<String, String> result = new ConcurrentHashMap<>();
        if (keys.isEmpty()) return result;
        List<CompletableFuture<Void>> tasks = keys.stream().distinct()
                .map(key -> CompletableFuture.runAsync(() -> {
                    try { result.put(key, awsService.getViewablePreSignedUrl(key)); } catch (Exception ignored) {}
                }, taskExecutor))
                .collect(Collectors.toList());
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        return result;
    }

    private StaffDetailResponse buildDetailResponse(StaffEntity staff,
                                                     List<StaffEducationHistoryEntity> education,
                                                     List<StaffFamilyLineageEntity> family,
                                                     List<StaffInsuranceCoverageEntity> insurance,
                                                     List<StaffLegalVerificationEntity> legal,
                                                     List<StaffResidencyEntity> residency,
                                                     Map<String, String> presignedUrls) {
        String avatarKey = staff.getAvatar();
        return StaffDetailResponse.builder()
                .uuid(staff.getUuid())
                .avatarObjectKey(avatarKey)
                .avatarPresignedUrl(presign(presignedUrls, avatarKey))
                .name(staff.getName())
                .department(staff.getDepartment())
                .designation(staff.getDesignation())
                .workEmail(staff.getWorkEmail())
                .phone(staff.getPhone())
                .joiningDate(staff.getJoiningDate())
                .resignDate(staff.getResignDate())
                .active(staff.getActive())
                .archive(staff.getArchive())
                .educationHistory(education.stream()
                        .map(e -> StaffDetailResponse.EducationRow.builder()
                                .level(e.getLevel()).institute(e.getInstitute()).city(e.getCity())
                                .year(e.getYear()).percentage(e.getPercentage())
                                .certificateObjectKey(e.getCertificate())
                                .certificatePresignedUrl(presign(presignedUrls, e.getCertificate()))
                                .build())
                        .collect(Collectors.toList()))
                .familyLineage(family.stream()
                        .map(f -> StaffDetailResponse.FamilyRow.builder()
                                .fullName(f.getFullName()).relation(f.getRelation())
                                .contactNumber(f.getContactNumber())
                                .documentObjectKey(f.getDocument())
                                .documentPresignedUrl(presign(presignedUrls, f.getDocument()))
                                .build())
                        .collect(Collectors.toList()))
                .insuranceCoverage(insurance.stream()
                        .map(i -> StaffDetailResponse.InsuranceRow.builder()
                                .insuranceProvider(i.getInsuranceProvider()).policyId(i.getPolicyId())
                                .build())
                        .collect(Collectors.toList()))
                .legalVerification(legal.stream()
                        .map(l -> StaffDetailResponse.LegalRow.builder()
                                .documentType(l.getDocumentType())
                                .identificationNumber(l.getIdentificationNumber())
                                .documentObjectKey(l.getDocument())
                                .documentPresignedUrl(presign(presignedUrls, l.getDocument()))
                                .build())
                        .collect(Collectors.toList()))
                .residency(residency.stream()
                        .map(r -> StaffDetailResponse.ResidencyRow.builder()
                                .houseNo(r.getHouseNo()).lane(r.getLane()).city(r.getCity())
                                .state(r.getState()).pin(r.getPin()).type(r.getType())
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    private static String presign(Map<String, String> presignedUrls, String key) {
        return key != null ? presignedUrls.get(key) : null;
    }

    private void saveEducation(Long staffId, UUID staffUuid, List<StaffEducationItemRequest> items,
                                List<MultipartFile> files, Set<String> retainedDocumentKeys) throws VeloriaException {
        if (items.isEmpty()) return;
        List<StaffEducationHistoryEntity> entities = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            StaffEducationItemRequest item = items.get(i);
            String certificate = resolveUploadedOrExisting(
                    item.getCertificateUrl(), fileAt(files, i), staffUuid, "education");
            addKeyIfPresent(retainedDocumentKeys, certificate);
            entities.add(StaffEducationHistoryEntity.builder()
                    .staffId(staffId).level(item.getLevel()).institute(item.getInstitute())
                    .city(item.getCity()).year(item.getYear()).percentage(item.getPercentage())
                    .certificate(certificate).build());
        }
        educationHistoryRepository.saveAll(entities);
    }

    private void saveFamilyLineage(Long staffId, UUID staffUuid, List<StaffFamilyLineageItemRequest> items,
                                    List<MultipartFile> files, Set<String> retainedDocumentKeys) throws VeloriaException {
        if (items.isEmpty()) return;
        List<StaffFamilyLineageEntity> entities = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            StaffFamilyLineageItemRequest item = items.get(i);
            String document = resolveUploadedOrExisting(
                    item.getDocumentUrl(), fileAt(files, i), staffUuid, "family");
            addKeyIfPresent(retainedDocumentKeys, document);
            entities.add(StaffFamilyLineageEntity.builder()
                    .staffId(staffId).fullName(item.getFullName()).relation(item.getRelation())
                    .contactNumber(item.getContactNumber()).document(document).build());
        }
        familyLineageRepository.saveAll(entities);
    }

    private void saveInsurance(Long staffId, List<StaffInsuranceItemRequest> items) {
        if (items.isEmpty()) return;
        insuranceCoverageRepository.saveAll(items.stream()
                .map(i -> StaffInsuranceCoverageEntity.builder()
                        .staffId(staffId).insuranceProvider(i.getInsuranceProvider()).policyId(i.getPolicyId())
                        .build())
                .collect(Collectors.toList()));
    }

    private void saveLegal(Long staffId, UUID staffUuid, List<StaffLegalVerificationItemRequest> items,
                            List<MultipartFile> files, Set<String> retainedDocumentKeys) throws VeloriaException {
        if (items.isEmpty()) return;
        List<StaffLegalVerificationEntity> entities = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            StaffLegalVerificationItemRequest item = items.get(i);
            String document = resolveUploadedOrExisting(item.getDocumentUrl(), fileAt(files, i), staffUuid, "legal");
            addKeyIfPresent(retainedDocumentKeys, document);
            entities.add(StaffLegalVerificationEntity.builder()
                    .staffId(staffId).documentType(item.getDocumentType())
                    .identificationNumber(item.getIdentificationNumber()).document(document).build());
        }
        legalVerificationRepository.saveAll(entities);
    }

    private void saveResidency(Long staffId, List<StaffResidencyItemRequest> items) {
        residencyRepository.saveAll(items.stream()
                .map(item -> StaffResidencyEntity.builder()
                        .staffId(staffId).houseNo(item.getHouseNo()).lane(item.getLane())
                        .city(item.getCity()).state(item.getState()).pin(item.getPin())
                        .type(item.getResidencyType().name()).build())
                .collect(Collectors.toList()));
    }

    private String resolveUploadedOrExisting(String existingKey, MultipartFile file,
                                              UUID staffUuid, String segment) throws VeloriaException {
        if (file != null && !file.isEmpty()) {
            try {
                String path = switch (segment) {
                    case "education" -> awsService.getStaffEducationDocumentPath(staffUuid, file.getOriginalFilename());
                    case "family"    -> awsService.getStaffFamilyDocumentPath(staffUuid, file.getOriginalFilename());
                    case "legal"     -> awsService.getStaffLegalDocumentPath(staffUuid, file.getOriginalFilename());
                    default -> throw new VeloriaException(ResponseCode.BAD_REQUEST, "Unknown segment: " + segment);
                };
                return awsService.uploadDocumentMultipart(file, path);
            } catch (IOException e) {
                throw new VeloriaException(ResponseCode.AWS_ERROR, "Document upload failed: " + e.getMessage());
            }
        }
        return Strings.isNullOrEmpty(existingKey) ? null : existingKey;
    }

    private MultipartFile fileAt(List<MultipartFile> files, int index) {
        if (files == null || index >= files.size()) return null;
        MultipartFile f = files.get(index);
        return (f == null || f.isEmpty()) ? null : f;
    }

    private void validateResidency(List<StaffResidencyItemRequest> residency) throws VeloriaException {
        if (residency == null || residency.size() != 2) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Residency must contain exactly two entries: one PERMANENT and one CURRENT");
        }
        boolean permanent = false, current = false;
        for (StaffResidencyItemRequest row : residency) {
            if (row.getResidencyType() == null)
                throw new VeloriaException(ResponseCode.BAD_REQUEST, "residencyType is required on each residency row");
            if (row.getResidencyType() == StaffResidencyType.PERMANENT) permanent = true;
            if (row.getResidencyType() == StaffResidencyType.CURRENT) current = true;
        }
        if (!permanent || !current)
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Residency must include exactly one PERMANENT and one CURRENT address");
    }

    private void deleteStaleS3Object(String previousKey, String newKey) {
        if (Strings.isNullOrEmpty(previousKey) || previousKey.equals(newKey)) return;
        try { awsService.deleteKey(previousKey); } catch (Exception ignored) {}
    }

    private static void addKeyIfPresent(Set<String> keys, String key) {
        if (!Strings.isNullOrEmpty(key)) keys.add(key);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }
}
