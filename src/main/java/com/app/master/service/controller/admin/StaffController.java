package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.dto.StaffUpsertRequest;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.StaffService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/staff")
public class StaffController extends AppController {

    private final StaffService staffService;

    public StaffController(StaffService staffService) {
        this.staffService = staffService;
    }

    @PostMapping(value = "/create", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Response> createStaff(@RequestPart("payload") StaffUpsertRequest payload,
                                                @RequestPart(value = "avatar", required = false) MultipartFile avatar,
                                                @RequestPart(value = "educationFiles", required = false) List<MultipartFile> educationFiles,
                                                @RequestPart(value = "familyLineageFiles", required = false) List<MultipartFile> familyLineageFiles,
                                                @RequestPart(value = "legalVerificationFiles", required = false) List<MultipartFile> legalVerificationFiles) throws VeloriaException {

        staffService.createStaff(payload, avatar, educationFiles, familyLineageFiles, legalVerificationFiles);
        return success(ResponseCode.CREATED, "Staff created successfully");
    }

    @PutMapping(value = "/update/{staffUuid}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Response> updateStaff(@PathVariable UUID staffUuid,
                                                @RequestPart("payload") StaffUpsertRequest payload,
                                                @RequestPart(value = "avatar", required = false) MultipartFile avatar,
                                                @RequestPart(value = "educationFiles", required = false) List<MultipartFile> educationFiles,
                                                @RequestPart(value = "familyLineageFiles", required = false) List<MultipartFile> familyLineageFiles,
                                                @RequestPart(value = "legalVerificationFiles", required = false) List<MultipartFile> legalVerificationFiles) throws VeloriaException {

        staffService.updateStaff(staffUuid, payload, avatar, educationFiles, familyLineageFiles, legalVerificationFiles);
        return success(ResponseCode.UPDATED, "Staff updated successfully");
    }

    @GetMapping("/{staffUuid}")
    public ResponseEntity<Response> getStaffByUuid(@PathVariable UUID staffUuid) throws VeloriaException {

        return data(ResponseCode.FETCHED, "Staff fetched successfully", staffService.getStaffByUuid(staffUuid));
    }

}