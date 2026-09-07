package com.app.master.service.service.admin;

import com.app.master.service.core.entity.*;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.*;
import com.app.master.service.service.admin.provider.GstinVerificationProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * e-Invoice and e-Way bill applicability, and GSTIN verification
 * (spec phases 23, 24 and 25).
 *
 * This service decides whether a document is required and records that
 * decision. It never produces an IRN, an acknowledgement number or an e-way
 * bill number — those are government-issued, and database constraints prevent
 * storing an IRN without an acknowledgement.
 *
 * Thresholds are configuration, not code: a turnover or consignment-value limit
 * that changes by notification is read from gst_configuration, and an
 * unconfigured threshold yields REQUIRES_REVIEW rather than a guess.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstComplianceDocumentService {

    public static final String APPLICABLE      = "APPLICABLE";
    public static final String NOT_APPLICABLE  = "NOT_APPLICABLE";
    public static final String REQUIRES_REVIEW = "REQUIRES_REVIEW";

    private final EInvoiceDocumentRepository eInvoiceRepo;
    private final EWayBillDocumentRepository eWayBillRepo;
    private final GstinVerificationRepository verificationRepo;
    private final SalesInvoiceRepository invoiceRepo;
    private final GstConfigurationService configService;
    private final GstIdentityService identityService;
    private final com.app.master.service.service.admin.provider.EinvoiceProvider einvoiceProvider;
    private final com.app.master.service.service.admin.provider.EwayBillProvider ewayBillProvider;
    private final GstAuditService auditService;
    private final List<GstinVerificationProvider> verificationProviders;
    private final com.app.master.service.core.security.GstSecurityContext securityContext;

    private Long orgId() {
        return securityContext.current()
                .map(com.app.master.service.core.security.GstPrincipal::organizationId)
                .filter(Objects::nonNull)
                .orElseGet(identityService::defaultOrganizationId);
    }

    // ── e-Invoice (spec phase 23) ────────────────────────────────────────────

    /**
     * Decides whether an invoice needs an e-invoice, and records the decision.
     *
     * Inputs are the registration, the configured turnover threshold, the
     * document type and the customer type. With no threshold configured the
     * answer is REQUIRES_REVIEW — the application will not assume one.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public EInvoiceDocumentEntity assessEInvoice(Long salesInvoiceId) throws VeloriaException {
        SalesInvoiceEntity inv = loadInvoice(salesInvoiceId);

        Optional<EInvoiceDocumentEntity> existing = eInvoiceRepo.findBySalesInvoiceId(salesInvoiceId);
        if (existing.isPresent()) return existing.get();

        Long org = orgId();
        String applicability;
        String reason;

        boolean enabled = configService.booleanValue(org, GstConfigurationService.EINVOICE_ENABLED, false);
        Optional<Long> threshold = configService.longValue(org,
                GstConfigurationService.EINVOICE_TURNOVER_THRESHOLD_PAISE);

        if (!enabled) {
            applicability = NOT_APPLICABLE;
            reason = "e-Invoicing is not enabled for this organization. "
                    + "Set EINVOICE_ENABLED once the business crosses the notified turnover threshold.";
        } else if (threshold.isEmpty()) {
            applicability = REQUIRES_REVIEW;
            reason = "e-Invoicing is enabled but EINVOICE_TURNOVER_THRESHOLD_PAISE is not configured. "
                    + "The applicable turnover threshold is a statutory value and must be supplied.";
        } else if (!"B2B".equals(inv.getCustomerType())) {
            applicability = NOT_APPLICABLE;
            reason = "e-Invoicing applies to B2B supplies; this invoice is " + inv.getCustomerType() + ".";
        } else if (inv.getCustomerGstin() == null || inv.getCustomerGstin().isBlank()) {
            applicability = REQUIRES_REVIEW;
            reason = "Invoice is marked B2B but carries no customer GSTIN.";
        } else {
            applicability = APPLICABLE;
            reason = "B2B supply with a customer GSTIN, and e-invoicing is enabled for this organization.";
        }

        EInvoiceDocumentEntity doc = eInvoiceRepo.save(EInvoiceDocumentEntity.builder()
                .salesInvoiceId(inv.getId())
                .documentNumber(inv.getInvoiceNumber())
                .documentDate(inv.getInvoiceDate())
                .applicability(applicability)
                .applicabilityReason(reason)
                .submissionStatus("NOT_SUBMITTED")
                .organizationId(inv.getOrganizationId())
                .gstRegistrationId(inv.getGstRegistrationId())
                .createdBy(securityContext.actor())
                .build());

        auditService.log("EINVOICE_DOCUMENT", doc.getId(), inv.getInvoiceNumber(),
                "EINVOICE_ASSESSED", inv.getTaxPeriod(), securityContext.actor());
        log.info("e-Invoice applicability for {}: {} — {}", inv.getInvoiceNumber(), applicability, reason);
        return doc;
    }

    /**
     * Records an IRN obtained from the IRP.
     *
     * Both the IRN and the acknowledgement number are required, because one
     * without the other is not a real registration. Nothing in this application
     * generates either value.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public EInvoiceDocumentEntity recordIrn(Long documentId, String irn, String acknowledgementNumber,
                                             String signedQrPayload, String provider) throws VeloriaException {
        if (irn == null || irn.isBlank() || acknowledgementNumber == null || acknowledgementNumber.isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Both an IRN and an acknowledgement number from the IRP are required. "
                            + "This application does not generate either value.");
        }
        EInvoiceDocumentEntity doc = eInvoiceRepo.findById(documentId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "e-Invoice document not found: " + documentId));

        doc.setIrn(irn.trim());
        doc.setAcknowledgementNumber(acknowledgementNumber.trim());
        doc.setAcknowledgementDate(Instant.now());
        doc.setSignedQrPayload(signedQrPayload);
        doc.setProvider(provider != null ? provider : "MANUAL_ENTRY");
        doc.setSubmissionStatus("ACKNOWLEDGED");
        eInvoiceRepo.save(doc);

        auditService.log("EINVOICE_DOCUMENT", doc.getId(), doc.getDocumentNumber(),
                "EINVOICE_IRN_RECORDED", null, null, irn, null, securityContext.actor());
        return doc;
    }

    // ── e-Way bill (spec phase 24) ───────────────────────────────────────────

    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public EWayBillDocumentEntity assessEWayBill(Long salesInvoiceId) throws VeloriaException {
        SalesInvoiceEntity inv = loadInvoice(salesInvoiceId);

        Optional<EWayBillDocumentEntity> existing = eWayBillRepo.findBySalesInvoiceId(salesInvoiceId);
        if (existing.isPresent()) return existing.get();

        Long org = orgId();
        long consignmentValue = inv.getTotalInvoiceValue() != null ? inv.getTotalInvoiceValue() : 0L;

        boolean enabled = configService.booleanValue(org, GstConfigurationService.EWAYBILL_ENABLED, false);
        Optional<Long> threshold = configService.longValue(org,
                GstConfigurationService.EWAYBILL_VALUE_THRESHOLD_PAISE);

        String applicability;
        String reason;

        if (!enabled) {
            applicability = NOT_APPLICABLE;
            reason = "e-Way bill generation is not enabled for this organization.";
        } else if (threshold.isEmpty()) {
            applicability = REQUIRES_REVIEW;
            reason = "e-Way bill is enabled but EWAYBILL_VALUE_THRESHOLD_PAISE is not configured. "
                    + "The consignment value threshold varies by state and must be supplied.";
        } else if (consignmentValue >= threshold.get()) {
            applicability = APPLICABLE;
            reason = "Consignment value " + rupees(consignmentValue)
                    + " meets the configured threshold " + rupees(threshold.get()) + ".";
        } else {
            applicability = NOT_APPLICABLE;
            reason = "Consignment value " + rupees(consignmentValue)
                    + " is below the configured threshold " + rupees(threshold.get()) + ".";
        }

        EWayBillDocumentEntity doc = eWayBillRepo.save(EWayBillDocumentEntity.builder()
                .salesInvoiceId(inv.getId())
                .documentNumber(inv.getInvoiceNumber())
                .documentDate(inv.getInvoiceDate())
                .applicability(applicability)
                .applicabilityReason(reason)
                .consignmentValuePaise(consignmentValue)
                .originStateCode(inv.getSellerStateCode())
                .destinationStateCode(inv.getPlaceOfSupply())
                .submissionStatus("NOT_SUBMITTED")
                .organizationId(inv.getOrganizationId())
                .createdBy(securityContext.actor())
                .build());

        auditService.log("EWAYBILL_DOCUMENT", doc.getId(), inv.getInvoiceNumber(),
                "EWAYBILL_ASSESSED", inv.getTaxPeriod(), securityContext.actor());
        log.info("e-Way bill applicability for {}: {} — {}", inv.getInvoiceNumber(), applicability, reason);
        return doc;
    }

    /** Records an e-way bill number issued by the portal. Never generated here. */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public EWayBillDocumentEntity recordEwbNumber(Long documentId, String ewbNumber,
                                                   String provider) throws VeloriaException {
        if (ewbNumber == null || ewbNumber.isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "An e-way bill number from the portal is required. "
                            + "This application does not generate one.");
        }
        EWayBillDocumentEntity doc = eWayBillRepo.findById(documentId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "e-Way bill document not found: " + documentId));
        doc.setEwbNumber(ewbNumber.trim());
        doc.setEwbDate(Instant.now());
        doc.setProvider(provider != null ? provider : "MANUAL_ENTRY");
        doc.setSubmissionStatus("ACKNOWLEDGED");
        eWayBillRepo.save(doc);

        auditService.log("EWAYBILL_DOCUMENT", doc.getId(), doc.getDocumentNumber(),
                "EWAYBILL_NUMBER_RECORDED", null, null, ewbNumber, null, securityContext.actor());
        return doc;
    }

    // ── GSTIN verification (spec phase 25) ───────────────────────────────────

    /**
     * Verifies a GSTIN using the best available provider.
     *
     * The government provider is preferred when configured; otherwise the local
     * format validator runs and the result is recorded as FORMAT_VALID, which
     * is explicitly not the same as VERIFIED.
     */
    // rollbackFor: VeloriaException is a checked exception, and Spring only
    // rolls back on unchecked ones by default — without this a validation
    // failure part-way through would leave the earlier writes committed.
    @Transactional(rollbackFor = Exception.class)
    public GstinVerificationEntity verifyGstin(String gstin) throws VeloriaException {
        if (gstin == null || gstin.isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "A GSTIN is required");
        }
        Long org = orgId();
        String normalised = gstin.trim().toUpperCase();

        GstinVerificationProvider provider = verificationProviders.stream()
                .filter(p -> !"LOCAL_FORMAT".equals(p.name()))
                .filter(GstinVerificationProvider::isConfigured)
                .findFirst()
                .orElseGet(() -> verificationProviders.stream()
                        .filter(p -> "LOCAL_FORMAT".equals(p.name()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No GSTIN verification provider available")));

        GstinVerificationProvider.Result result = provider.verify(normalised);

        GstinVerificationEntity record = verificationRepo
                .findByOrganizationIdAndGstin(org, normalised)
                .orElseGet(() -> GstinVerificationEntity.builder()
                        .gstin(normalised).organizationId(org).build());

        record.setStatus(result.status());
        record.setLegalName(result.legalName());
        record.setTradeName(result.tradeName());
        record.setStateCode(result.stateCode());
        record.setRegistrationStatus(result.registrationStatus());
        record.setProviderReference(result.providerReference());
        record.setLastError(result.error());

        // Only a real provider result may be recorded as VERIFIED; the database
        // additionally refuses VERIFIED without a provider and timestamp.
        if ("VERIFIED".equals(result.status())) {
            record.setProvider(provider.name());
            record.setVerifiedAt(Instant.now());
        } else {
            record.setProvider(provider.name());
        }
        verificationRepo.save(record);

        log.info("GSTIN {} checked by {}: {}",
                GstIdentityService.maskGstin(normalised), provider.name(), result.status());
        return record;
    }

    public List<GstinVerificationEntity> verifications() throws VeloriaException {
        return verificationRepo.findByOrganizationIdOrderByIdDesc(securityContext.organizationId());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private SalesInvoiceEntity loadInvoice(Long id) throws VeloriaException {
        SalesInvoiceEntity inv = invoiceRepo.findById(id)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Invoice not found: " + id));
        Long org = orgId();
        if (inv.getOrganizationId() != null && org != null && !inv.getOrganizationId().equals(org)) {
            throw new VeloriaException(ResponseCode.NOT_FOUND, "Invoice not found: " + id);
        }
        return inv;
    }

    private static String rupees(long paise) {
        return "Rs " + String.format("%.2f", paise / 100.0);
    }

    // ── Submission through the configured providers (phases 7 and 8) ─────────

    /**
     * Submits an assessed e-invoice to the IRP.
     *
     * The response is persisted only when it carries both an IRN and an
     * acknowledgement number. An unconfigured provider returns
     * EXTERNAL_DEPENDENCY and the document is left untouched — never marked
     * registered on the strength of a stub.
     */
    @Transactional(rollbackFor = Exception.class)
    public EInvoiceDocumentEntity submitEInvoice(Long documentId, String payload)
            throws VeloriaException {
        EInvoiceDocumentEntity doc = eInvoiceRepo.findById(documentId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "e-Invoice document not found: " + documentId));

        if (NOT_APPLICABLE.equals(doc.getApplicability())) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "e-Invoicing does not apply to this invoice: " + doc.getApplicabilityReason());
        }

        var result = einvoiceProvider.register(doc.getSalesInvoiceId(), payload);

        if (!result.isGenerated()) {
            doc.setSubmissionStatus(result.status());
            doc.setLastError(result.error());
            doc.setProvider(einvoiceProvider.name());
            eInvoiceRepo.save(doc);
            auditService.log("EINVOICE_DOCUMENT", doc.getId(), doc.getDocumentNumber(),
                    "EINVOICE_SUBMISSION_FAILED", null, null, result.error(), null,
                    securityContext.actor());
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "e-Invoice was not registered: " + result.error());
        }

        return recordIrn(documentId, result.irn(), result.acknowledgementNumber(),
                result.signedQrCode(), einvoiceProvider.name());
    }

    /** Submits an assessed e-way bill to the portal, under the same rules. */
    @Transactional(rollbackFor = Exception.class)
    public EWayBillDocumentEntity submitEWayBill(Long documentId, String payload)
            throws VeloriaException {
        EWayBillDocumentEntity doc = eWayBillRepo.findById(documentId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "e-Way bill document not found: " + documentId));

        if (NOT_APPLICABLE.equals(doc.getApplicability())) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "An e-way bill does not apply to this invoice: " + doc.getApplicabilityReason());
        }

        var result = ewayBillProvider.generate(doc.getSalesInvoiceId(), payload);

        if (!result.isGenerated()) {
            doc.setSubmissionStatus(result.status());
            doc.setLastError(result.error());
            doc.setProvider(ewayBillProvider.name());
            eWayBillRepo.save(doc);
            auditService.log("EWAYBILL_DOCUMENT", doc.getId(), doc.getDocumentNumber(),
                    "EWAYBILL_SUBMISSION_FAILED", null, null, result.error(), null,
                    securityContext.actor());
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "e-Way bill was not generated: " + result.error());
        }

        return recordEwbNumber(documentId, result.ewbNumber(), ewayBillProvider.name());
    }

    /** What the configured integrations can actually do right now. */
    public java.util.Map<String, Object> providerStatus() {
        return java.util.Map.of(
                "einvoice", java.util.Map.of(
                        "provider", einvoiceProvider.name(),
                        "configured", einvoiceProvider.isConfigured(),
                        "status", einvoiceProvider.isConfigured() ? "CONFIGURED" : "EXTERNAL_DEPENDENCY"),
                "ewayBill", java.util.Map.of(
                        "provider", ewayBillProvider.name(),
                        "configured", ewayBillProvider.isConfigured(),
                        "status", ewayBillProvider.isConfigured() ? "CONFIGURED" : "EXTERNAL_DEPENDENCY"));
    }
}
