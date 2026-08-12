package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.dto.RoleUpsertRequest;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.service.admin.RoleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/roles")
public class RoleController extends AppController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @PostMapping
    public ResponseEntity<Response> createRole(@RequestBody RoleUpsertRequest request) throws VeloriaException {
        return success(ResponseCode.CREATED, "Role created", roleService.createRole(request));
    }

    @GetMapping
    public ResponseEntity<Response> getRoles(@RequestParam(required = false) String department) {
        return success(ResponseCode.FETCHED, "Roles retrieved", roleService.getRoles(department));
    }

    @GetMapping("/effective")
    public ResponseEntity<Response> getEffectivePermissions(@RequestParam UUID staffUuid) throws VeloriaException {
        return success(ResponseCode.FETCHED, "Effective permissions resolved", roleService.getEffectivePermissions(staffUuid));
    }

    @GetMapping("/{roleUuid}")
    public ResponseEntity<Response> getRole(@PathVariable UUID roleUuid) throws VeloriaException {
        return success(ResponseCode.FETCHED, "Role retrieved", roleService.getRoleByUuid(roleUuid));
    }

    @PutMapping("/{roleUuid}")
    public ResponseEntity<Response> updateRole(@PathVariable UUID roleUuid,
                                               @RequestBody RoleUpsertRequest request) throws VeloriaException {
        return success(ResponseCode.UPDATED, "Role updated", roleService.updateRole(roleUuid, request));
    }

    @DeleteMapping("/{roleUuid}")
    public ResponseEntity<Response> deleteRole(@PathVariable UUID roleUuid) throws VeloriaException {
        roleService.deleteRole(roleUuid);
        return success(ResponseCode.DELETED, "Role deleted", null);
    }
}
