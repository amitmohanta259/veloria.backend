package com.app.master.service.service.admin;

import com.app.master.service.core.entity.BusinessDetailsEntity;
import com.app.master.service.core.entity.GstRegistrationEntity;
import com.app.master.service.core.entity.OrganizationEntity;
import com.app.master.service.repository.admin.BusinessDetailsRepository;
import com.app.master.service.repository.admin.GstRegistrationRepository;
import com.app.master.service.repository.admin.OrganizationRepository;
import com.app.master.service.repository.admin.GstStateMasterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * GSTIN validation and place-of-supply resolution.
 *
 * A GSTIN encodes its own state: characters 1-2 are the state code. The audit
 * found business_details holding GSTIN 21... (Odisha) alongside
 * seller_state_code 20 (Jharkhand), which would misclassify every intra-state
 * sale once buyer state resolution started working. The seller state code is
 * therefore derived from the GSTIN rather than trusted from its own column.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstIdentityService {

    /** 2-digit state code, 10-char PAN, entity number, 'Z', checksum. */
    private static final Pattern GSTIN_PATTERN =
            Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$");

    private final BusinessDetailsRepository businessDetailsRepo;
    private final GstStateMasterRepository stateMasterRepo;
    private final GstRegistrationRepository registrationRepo;
    private final OrganizationRepository organizationRepo;

    // ── Registration-backed lookups (spec section 4) ─────────────────────────

    /** The organization to scope GST data by when no principal is available. */
    public Long defaultOrganizationId() {
        return organizationRepo.findFirstByActiveTrueOrderByIdAsc()
                .map(OrganizationEntity::getId).orElse(null);
    }

    public Optional<GstRegistrationEntity> registration(Long registrationId) {
        return registrationId == null ? Optional.empty() : registrationRepo.findById(registrationId);
    }

    /** The registration to use for an organization, preferring the primary one. */
    public Optional<GstRegistrationEntity> primaryRegistration(Long organizationId) {
        if (organizationId == null) return Optional.empty();
        return registrationRepo.findFirstByOrganizationIdAndIsPrimaryTrue(organizationId)
                .or(() -> registrationRepo.findFirstByOrganizationIdAndIsActiveTrueOrderByIdAsc(organizationId));
    }

    /**
     * The registration that should raise a supply from a given place of supply.
     * With one registration this always returns it; with several it prefers the
     * one registered in the supplying state.
     */
    public Optional<GstRegistrationEntity> registrationFor(Long organizationId, String placeOfSupply) {
        if (organizationId == null) return Optional.empty();
        if (placeOfSupply != null && !placeOfSupply.isBlank()) {
            Optional<GstRegistrationEntity> inState = registrationRepo
                    .findFirstByOrganizationIdAndStateCodeAndIsActiveTrue(organizationId, placeOfSupply.trim());
            if (inState.isPresent()) return inState;
        }
        return primaryRegistration(organizationId);
    }

    public boolean isValidGstin(String gstin) {
        return gstin != null && GSTIN_PATTERN.matcher(gstin.trim().toUpperCase()).matches();
    }

    /** The state code a GSTIN declares, or null if the GSTIN is malformed. */
    public String stateCodeOf(String gstin) {
        if (!isValidGstin(gstin)) return null;
        return gstin.trim().substring(0, 2);
    }

    public boolean isKnownStateCode(String stateCode) {
        if (stateCode == null || stateCode.isBlank()) return false;
        return stateMasterRepo.findByStateCodeAndActiveTrue(stateCode.trim()).isPresent();
    }

    public Optional<BusinessDetailsEntity> business() {
        return businessDetailsRepo.findFirstByArchiveFalseOrderByIdAsc();
    }

    /**
     * The GSTIN to stamp on outgoing documents. Prefers the organization's
     * registration; falls back to business_details for callers that have no
     * tenant context yet.
     */
    public String businessGstin() {
        return primaryRegistration(defaultOrganizationId())
                .map(GstRegistrationEntity::getGstin)
                .orElseGet(() -> business().map(BusinessDetailsEntity::getGstNumber).orElse(null));
    }

    /**
     * Seller state code, derived from the GSTIN rather than a stored column.
     * The audit found business_details holding GSTIN 21... (Odisha) alongside
     * seller_state_code 20 (Jharkhand); the GSTIN is authoritative.
     */
    public String sellerStateCode() {
        Optional<GstRegistrationEntity> reg = primaryRegistration(defaultOrganizationId());
        if (reg.isPresent()) {
            String declared = reg.get().declaredStateCode();
            if (declared != null) return declared;
        }

        Optional<BusinessDetailsEntity> biz = business();
        if (biz.isEmpty()) return null;

        String stored = biz.get().getSellerStateCode();
        String fromGstin = stateCodeOf(biz.get().getGstNumber());

        if (fromGstin == null) return stored;
        if (stored != null && !fromGstin.equals(stored.trim())) {
            log.warn("Seller state code mismatch: business_details.seller_state_code={} but GSTIN {} declares state {}. Using {}.",
                    stored, maskGstin(biz.get().getGstNumber()), fromGstin, fromGstin);
        }
        return fromGstin;
    }

    /** Masks a GSTIN for general-purpose logs (spec section 46). */
    public static String maskGstin(String gstin) {
        if (gstin == null || gstin.length() < 15) return "****";
        return gstin.substring(0, 2) + "*******" + gstin.substring(12);
    }

    /**
     * Whether a supply is inter-state.
     *
     * Throws when the place of supply is unknown. The previous behaviour
     * returned false (intra-state) for a null buyer state, which silently
     * charged CGST+SGST on what may have been an inter-state supply.
     */
    public boolean isInterState(String placeOfSupplyStateCode, String sellerStateCode) {
        if (placeOfSupplyStateCode == null || placeOfSupplyStateCode.isBlank()) {
            throw new IllegalStateException("Place of supply is unknown — cannot determine CGST/SGST vs IGST");
        }
        if (sellerStateCode == null || sellerStateCode.isBlank()) {
            throw new IllegalStateException("Seller state code is unknown — cannot determine CGST/SGST vs IGST");
        }
        return !placeOfSupplyStateCode.trim().equals(sellerStateCode.trim());
    }

    public String supplyType(String placeOfSupplyStateCode, String sellerStateCode) {
        return isInterState(placeOfSupplyStateCode, sellerStateCode) ? "INTER_STATE" : "INTRA_STATE";
    }
}
