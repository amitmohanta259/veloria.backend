package com.app.master.service.service.admin.impl;

import com.app.master.service.core.dto.RoleUpsertRequest;
import com.app.master.service.core.entity.RoleEntity;
import com.app.master.service.core.entity.RolePermissionEntity;
import com.app.master.service.core.entity.StaffEntity;
import com.app.master.service.core.enums.StaffDepartment;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.admin.EffectiveRoleResponse;
import com.app.master.service.core.response.admin.RoleResponse;
import com.app.master.service.repository.admin.RolePermissionRepository;
import com.app.master.service.repository.admin.RoleRepository;
import com.app.master.service.repository.admin.StaffRepository;
import com.app.master.service.service.admin.RoleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RoleServiceImpl implements RoleService {

    private static final List<String> ALL_MODULES = List.of(
            "DASHBOARD", "ANALYTICS", "INVENTORY", "STAFF",
            "SALES", "SUPPLIERS", "FINANCIALS", "RETURNS", "SETTINGS"
    );
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM dd, yyyy");

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final StaffRepository staffRepository;

    public RoleServiceImpl(RoleRepository roleRepository,
                           RolePermissionRepository rolePermissionRepository,
                           StaffRepository staffRepository) {
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.staffRepository = staffRepository;
    }

    @Override
    @Transactional
    public RoleResponse createRole(RoleUpsertRequest request) throws VeloriaException {
        RoleEntity role = RoleEntity.builder()
                .department(request.getDepartment())
                .designation(request.getDesignation())
                .staffUuid(request.getStaffUuid())
                .build();
        roleRepository.save(role);
        savePermissions(role.getId(), request.getPermissions());
        return toResponse(role, loadPermissions(role.getId()));
    }

    @Override
    public List<RoleResponse> getRoles(String department) {
        List<RoleEntity> roles = (department != null && !department.isBlank())
                ? roleRepository.findByDepartmentOrderByCreatedDesc(department)
                : roleRepository.findAllByOrderByDepartmentAscCreatedDesc();
        return roles.stream().map(r -> toResponse(r, List.of())).toList();
    }

    @Override
    public RoleResponse getRoleByUuid(UUID uuid) throws VeloriaException {
        RoleEntity role = roleRepository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Role not found"));
        return toResponse(role, loadPermissions(role.getId()));
    }

    @Override
    @Transactional
    public RoleResponse updateRole(UUID uuid, RoleUpsertRequest request) throws VeloriaException {
        RoleEntity role = roleRepository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Role not found"));
        role.setDepartment(request.getDepartment());
        role.setDesignation(request.getDesignation());
        role.setStaffUuid(request.getStaffUuid());
        roleRepository.save(role);
        rolePermissionRepository.deleteByRoleId(role.getId());
        savePermissions(role.getId(), request.getPermissions());
        return toResponse(role, loadPermissions(role.getId()));
    }

    @Override
    @Transactional
    public void deleteRole(UUID uuid) throws VeloriaException {
        RoleEntity role = roleRepository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Role not found"));
        rolePermissionRepository.deleteByRoleId(role.getId());
        roleRepository.delete(role);
    }

    @Override
    public EffectiveRoleResponse getEffectivePermissions(UUID staffUuid) throws VeloriaException {
        StaffEntity staff = staffRepository.findByUuid(staffUuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Staff not found"));

        String dept = staff.getDepartment() != null ? staff.getDepartment().name() : null;
        String designation = staff.getDesignation();

        // 1 — individual override
        RoleEntity role = roleRepository.findByStaffUuid(staffUuid).orElse(null);
        String level = "INDIVIDUAL";

        // 2 — designation level
        if (role == null && dept != null && designation != null && !designation.isBlank()) {
            role = roleRepository
                    .findByDepartmentAndDesignationIgnoreCaseAndStaffUuidIsNull(dept, designation)
                    .orElse(null);
            level = "DESIGNATION";
        }

        // 3 — department level
        if (role == null && dept != null) {
            role = roleRepository
                    .findByDepartmentAndDesignationIsNullAndStaffUuidIsNull(dept)
                    .orElse(null);
            level = "DEPARTMENT";
        }

        // 4 — no role configured
        if (role == null) {
            List<RoleResponse.PermissionRow> empty = ALL_MODULES.stream().map(m ->
                    RoleResponse.PermissionRow.builder()
                            .module(m).canView(false).canCreate(false).canEdit(false).canDelete(false)
                            .build()
            ).toList();
            return EffectiveRoleResponse.builder()
                    .appliedLevel("GLOBAL")
                    .scopeLabel("No role configured")
                    .permissions(empty)
                    .build();
        }

        List<RolePermissionEntity> perms = loadPermissions(role.getId());
        Map<String, RolePermissionEntity> permMap = perms.stream()
                .collect(Collectors.toMap(RolePermissionEntity::getModule, p -> p));

        List<RoleResponse.PermissionRow> rows = ALL_MODULES.stream().map(m -> {
            RolePermissionEntity p = permMap.get(m);
            return RoleResponse.PermissionRow.builder()
                    .module(m)
                    .canView(p != null && Boolean.TRUE.equals(p.getCanView()))
                    .canCreate(p != null && Boolean.TRUE.equals(p.getCanCreate()))
                    .canEdit(p != null && Boolean.TRUE.equals(p.getCanEdit()))
                    .canDelete(p != null && Boolean.TRUE.equals(p.getCanDelete()))
                    .build();
        }).toList();

        String staffName = staff.getName() != null ? staff.getName() : staff.getWorkEmail();
        return EffectiveRoleResponse.builder()
                .appliedLevel(level)
                .scopeLabel(buildScopeLabel(role.getDepartment(), role.getDesignation(), level.equals("INDIVIDUAL") ? staffName : null))
                .permissions(rows)
                .build();
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private void savePermissions(Long roleId, List<RoleUpsertRequest.PermissionRequest> perms) {
        if (perms == null || perms.isEmpty()) return;
        List<RolePermissionEntity> entities = perms.stream().map(p ->
                RolePermissionEntity.builder()
                        .roleId(roleId)
                        .module(p.getModule())
                        .canView(Boolean.TRUE.equals(p.getCanView()))
                        .canCreate(Boolean.TRUE.equals(p.getCanCreate()))
                        .canEdit(Boolean.TRUE.equals(p.getCanEdit()))
                        .canDelete(Boolean.TRUE.equals(p.getCanDelete()))
                        .build()
        ).toList();
        rolePermissionRepository.saveAll(entities);
    }

    private List<RolePermissionEntity> loadPermissions(Long roleId) {
        return rolePermissionRepository.findByRoleId(roleId);
    }

    private RoleResponse toResponse(RoleEntity role, List<RolePermissionEntity> perms) {
        Map<String, RolePermissionEntity> permMap = perms.stream()
                .collect(Collectors.toMap(RolePermissionEntity::getModule, p -> p));

        List<RoleResponse.PermissionRow> rows = ALL_MODULES.stream().map(m -> {
            RolePermissionEntity p = permMap.get(m);
            return RoleResponse.PermissionRow.builder()
                    .module(m)
                    .canView(p != null && Boolean.TRUE.equals(p.getCanView()))
                    .canCreate(p != null && Boolean.TRUE.equals(p.getCanCreate()))
                    .canEdit(p != null && Boolean.TRUE.equals(p.getCanEdit()))
                    .canDelete(p != null && Boolean.TRUE.equals(p.getCanDelete()))
                    .build();
        }).toList();

        String createdAt = role.getCreated() != null
                ? DATE_FMT.format(role.getCreated().atZone(java.time.ZoneOffset.UTC).toLocalDate())
                : "—";

        String staffName = null;
        if (role.getStaffUuid() != null) {
            staffName = staffRepository.findByUuid(role.getStaffUuid())
                    .map(s -> s.getName() != null ? s.getName() : s.getWorkEmail())
                    .orElse(role.getStaffUuid().toString());
        }

        return RoleResponse.builder()
                .uuid(role.getUuid())
                .department(role.getDepartment())
                .designation(role.getDesignation())
                .staffUuid(role.getStaffUuid())
                .staffName(staffName)
                .scopeLabel(buildScopeLabel(role.getDepartment(), role.getDesignation(), staffName))
                .memberCount(getMemberCount(role))
                .createdAt(createdAt)
                .permissions(rows)
                .build();
    }

    private String buildScopeLabel(String department, String designation, String staffName) {
        String deptLabel = department != null ? department.replace("_", " ") : "—";
        if (staffName != null) return deptLabel + " · " + (designation != null ? designation + " · " : "") + staffName;
        if (designation != null) return deptLabel + " · " + designation;
        return deptLabel;
    }

    private long getMemberCount(RoleEntity role) {
        if (role.getStaffUuid() != null) return 1L;
        if (role.getDepartment() == null) return 0L;
        try {
            StaffDepartment dept = StaffDepartment.valueOf(role.getDepartment());
            if (role.getDesignation() != null && !role.getDesignation().isBlank()) {
                return staffRepository.countByArchiveFalseAndDepartmentAndDesignationIgnoreCase(dept, role.getDesignation());
            }
            return staffRepository.countByArchiveFalseAndDepartment(dept);
        } catch (IllegalArgumentException e) {
            return 0L;
        }
    }
}
