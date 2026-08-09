package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.dto.StaffUpsertRequest;
import com.app.master.service.core.enums.StaffDepartment;
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

        return success(ResponseCode.CREATED, "Staff created successfully",
                staffService.createStaff(payload, avatar, educationFiles, familyLineageFiles, legalVerificationFiles));
    }

    @PutMapping(value = "/update/{staffUuid}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Response> updateStaff(@PathVariable UUID staffUuid,
                                                @RequestPart("payload") StaffUpsertRequest payload,
                                                @RequestPart(value = "avatar", required = false) MultipartFile avatar,
                                                @RequestPart(value = "educationFiles", required = false) List<MultipartFile> educationFiles,
                                                @RequestPart(value = "familyLineageFiles", required = false) List<MultipartFile> familyLineageFiles,
                                                @RequestPart(value = "legalVerificationFiles", required = false) List<MultipartFile> legalVerificationFiles) throws VeloriaException {

        return success(ResponseCode.UPDATED, "Staff updated successfully",
                staffService.updateStaff(staffUuid, payload, avatar, educationFiles, familyLineageFiles, legalVerificationFiles));
    }

    @GetMapping("/list")
    public ResponseEntity<Response> getStaffList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) StaffDepartment department,
            @RequestParam(required = false) Boolean active) throws VeloriaException {

        return success(ResponseCode.FETCHED, "Staff list retrieved successfully",
                staffService.getStaffList(page, pageSize, search, department, active));
    }

    @GetMapping("/{staffUuid}")
    public ResponseEntity<Response> getStaffByUuid(@PathVariable UUID staffUuid) throws VeloriaException {
        return success(ResponseCode.FETCHED, "Staff retrieved successfully",
                staffService.getStaffByUuid(staffUuid));
    }

    @PatchMapping("/{staffUuid}/toggle")
    public ResponseEntity<Response> toggleStaffActive(@PathVariable UUID staffUuid) throws VeloriaException {
        return success(ResponseCode.OK, "Staff status updated",
                staffService.toggleStaffActive(staffUuid));
    }

    @DeleteMapping("/{staffUuid}")
    public ResponseEntity<Response> archiveStaff(@PathVariable UUID staffUuid) throws VeloriaException {
        staffService.archiveStaff(staffUuid);
        return success(ResponseCode.DELETED, "Staff archived successfully", null);
    }

    @GetMapping("/stats")
    public ResponseEntity<Response> getStaffStats() {
        return success(ResponseCode.FETCHED, "Staff stats retrieved", staffService.getStaffStats());
    }
}
