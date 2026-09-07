package com.app.master.service.controller.admin;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.entity.GstConfigurationEntity;
import com.app.master.service.core.entity.GstRegistrationEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.GstRegistrationRepository;
import com.app.master.service.service.admin.GstAuditService;
import com.app.master.service.service.admin.GstConfigurationService;
import com.app.master.service.service.admin.GstIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * GST registration and configuration management (spec phases 17 and 63).
 *
 * Registrations are scoped to the caller's organization, so one organization
 * can never see or edit another's GSTINs. The state code is always derived from
 * the GSTIN rather than accepted from the client.
 */
@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/gst")
@RequiredArgsConstructor
public class GstRegistrationController extends AppController {

    private final GstRegistrationRepository registrationRepo;
    private final GstIdentityService identityService;
    private final GstConfigurationService configService;
    private final GstAuditService auditService;
    private final com.app.master.service.core.security.GstSecurityContext securityContext;

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/registrations")
    public ResponseEntity<Response> list() throws VeloriaException {
        return data(ResponseCode.FETCHED, "GST registrations",
                registrationRepo.findByOrganizationIdAndIsActiveTrueOrderByIdAsc(
                        securityContext.organizationId()));
    }

    @PreAuthorize("hasAuthority('ADMIN_GST')")
    @PostMapping("/registrations")
    public ResponseEntity<Response> create(@RequestBody RegistrationRequest body) throws VeloriaException {
        if (!identityService.isValidGstin(body.getGstin())) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "GSTIN is not valid. Expected 15 characters: 2-digit state code, PAN, entity number, Z, checksum.");
        }
        String gstin = body.getGstin().trim().toUpperCase();
        if (registrationRepo.findByGstin(gstin).isPresent()) {
            throw new VeloriaException(ResponseCode.ALREADY_EXIST,
                    "A registration for " + GstIdentityService.maskGstin(gstin) + " already exists");
        }

        Long org = securityContext.organizationId();
        // The state code comes from the GSTIN, never from the request body.
        String stateCode = identityService.stateCodeOf(gstin);
        boolean first = registrationRepo.findByOrganizationIdAndIsActiveTrueOrderByIdAsc(org).isEmpty();

        GstRegistrationEntity reg = registrationRepo.save(GstRegistrationEntity.builder()
                .organizationId(org)
                .gstin(gstin)
                .stateCode(stateCode)
                .legalName(body.getLegalName())
                .tradeName(body.getTradeName())
                .registrationType(body.getRegistrationType() != null ? body.getRegistrationType() : "REGULAR")
                .returnFrequency(body.getReturnFrequency() != null ? body.getReturnFrequency() : "MONTHLY")
                .registrationDate(body.getRegistrationDate() != null
                        ? LocalDate.parse(body.getRegistrationDate()) : null)
                .invoicePrefix(body.getInvoicePrefix() != null ? body.getInvoicePrefix() : "INV")
                .creditNotePrefix(body.getCreditNotePrefix() != null ? body.getCreditNotePrefix() : "CN")
                .debitNotePrefix(body.getDebitNotePrefix() != null ? body.getDebitNotePrefix() : "DN")
                .address(body.getAddress())
                .isActive(true)
                .isPrimary(first)
                .build());

        auditService.log("GST_REGISTRATION", reg.getId(), GstIdentityService.maskGstin(gstin),
                "REGISTRATION_CREATED", null, securityContext.actor());
        return data(ResponseCode.CREATED, "GST registration added", reg);
    }

    @PreAuthorize("hasAuthority('ADMIN_GST')")
    @PatchMapping("/registrations/{id}")
    public ResponseEntity<Response> update(@PathVariable Long id,
                                            @RequestBody RegistrationRequest body) throws VeloriaException {
        GstRegistrationEntity reg = load(id);
        String old = reg.getInvoicePrefix() + "/" + reg.getReturnFrequency();

        if (body.getLegalName() != null) reg.setLegalName(body.getLegalName());
        if (body.getTradeName() != null) reg.setTradeName(body.getTradeName());
        if (body.getReturnFrequency() != null) reg.setReturnFrequency(body.getReturnFrequency());
        if (body.getRegistrationType() != null) reg.setRegistrationType(body.getRegistrationType());
        if (body.getInvoicePrefix() != null) reg.setInvoicePrefix(body.getInvoicePrefix());
        if (body.getCreditNotePrefix() != null) reg.setCreditNotePrefix(body.getCreditNotePrefix());
        if (body.getDebitNotePrefix() != null) reg.setDebitNotePrefix(body.getDebitNotePrefix());
        if (body.getAddress() != null) reg.setAddress(body.getAddress());
        registrationRepo.save(reg);

        auditService.log("GST_REGISTRATION", reg.getId(), GstIdentityService.maskGstin(reg.getGstin()),
                "REGISTRATION_UPDATED", "settings", old,
                reg.getInvoicePrefix() + "/" + reg.getReturnFrequency(), null, securityContext.actor());
        return data(ResponseCode.UPDATED, "GST registration updated", reg);
    }

    // ── Configuration (spec phase 63) ────────────────────────────────────────

    @PreAuthorize("hasAuthority('VIEW_GST')")
    @GetMapping("/configuration")
    public ResponseEntity<Response> configuration() throws VeloriaException {
        return data(ResponseCode.FETCHED, "GST configuration",
                configService.forOrganization(securityContext.organizationId()));
    }

    @PreAuthorize("hasAuthority('ADMIN_GST')")
    @PostMapping("/configuration")
    public ResponseEntity<Response> saveConfiguration(@RequestBody Map<String, String> body)
            throws VeloriaException {
        String key = body.get("configKey");
        if (key == null || key.isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "configKey is required");
        }
        GstConfigurationEntity saved = configService.save(GstConfigurationEntity.builder()
                .organizationId(securityContext.organizationId())
                .configKey(key.trim())
                .configValue(body.get("configValue"))
                .valueType(body.getOrDefault("valueType", "STRING"))
                .description(body.get("description"))
                .sourceReference(body.get("sourceReference"))
                .effectiveFrom(LocalDate.now())
                .active(true)
                .updatedBy(securityContext.actor())
                .build());

        auditService.log("GST_CONFIGURATION", saved.getId(), key, "CONFIGURATION_CHANGED",
                key, null, body.get("configValue"), null, securityContext.actor());
        return data(ResponseCode.UPDATED, "Configuration saved", saved);
    }

    private GstRegistrationEntity load(Long id) throws VeloriaException {
        GstRegistrationEntity reg = registrationRepo.findById(id)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Registration not found: " + id));
        Long org = securityContext.organizationId();
        if (reg.getOrganizationId() != null && !reg.getOrganizationId().equals(org)) {
            throw new VeloriaException(ResponseCode.NOT_FOUND, "Registration not found: " + id);
        }
        return reg;
    }

    @lombok.Data
    public static class RegistrationRequest {
        private String gstin;
        private String legalName;
        private String tradeName;
        private String registrationType;
        private String returnFrequency;
        private String registrationDate;
        private String invoicePrefix;
        private String creditNotePrefix;
        private String debitNotePrefix;
        private String address;
    }
}
