package com.app.master.service.service.admin;

import com.app.master.service.core.entity.GstAccountingExceptionEntity;
import com.app.master.service.core.entity.GstMovementLedgerEntity;
import com.app.master.service.core.entity.SalesInvoiceEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.security.GstSecurityContext;
import com.app.master.service.repository.admin.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Finds GST problems in data that already exists, and turns each into a record
 * a human can review.
 *
 * The governing rule: <b>historical financial records are never rewritten
 * automatically.</b> A wrong place of supply changes the tax head, and a wrong
 * tax head changes what was owed — so the engine reports what it found and stops.
 * A person decides what happens next, and that decision is audited.
 *
 * Detection reuses {@link GstReconciliationService}, which already sweeps orders
 * and purchases, rather than restating those rules here. This class adds the
 * checks that sweep does not cover, and gives every finding a durable identity,
 * a severity, a tax period and a review trail.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstHistoricalExceptionService {

    // ── Review lifecycle ─────────────────────────────────────────────────────
    public static final String PENDING_REVIEW = "PENDING_REVIEW";
    public static final String UNDER_REVIEW   = "UNDER_REVIEW";
    public static final String APPROVED       = "APPROVED";
    public static final String REJECTED       = "REJECTED";
    public static final String CORRECTED      = "CORRECTED";

    private static final Set<String> TERMINAL = Set.of(APPROVED, REJECTED, CORRECTED);

    /** Statuses a review may move to from an open exception. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            PENDING_REVIEW, Set.of(UNDER_REVIEW, APPROVED, REJECTED, CORRECTED),
            UNDER_REVIEW,   Set.of(APPROVED, REJECTED, CORRECTED));

    public static final String CRITICAL = "CRITICAL";
    public static final String HIGH     = "HIGH";
    public static final String MEDIUM   = "MEDIUM";
    public static final String LOW      = "LOW";

    private final GstAccountingExceptionRepository exceptionRepo;
    private final GstReconciliationService reconciliationService;
    private final GstMovementLedgerRepository movementRepo;
    private final SalesInvoiceRepository invoiceRepo;
    private final GstRegistrationRepository registrationRepo;
    private final GstIdentityService identityService;
    private final GstAuditService auditService;
    private final GstSecurityContext securityContext;

    private Long orgId() {
        return securityContext.current()
                .map(com.app.master.service.core.security.GstPrincipal::organizationId)
                .filter(Objects::nonNull)
                .orElseGet(identityService::defaultOrganizationId);
    }

    private Long regId() {
        return securityContext.current()
                .map(com.app.master.service.core.security.GstPrincipal::gstRegistrationId)
                .filter(Objects::nonNull)
                .orElseGet(() -> identityService.primaryRegistration(orgId())
                        .map(com.app.master.service.core.entity.GstRegistrationEntity::getId)
                        .orElse(null));
    }

    /** One thing found wrong, before it becomes a persisted exception. */
    public record Detected(String type, String severity, String reference,
                           String description, String sourceType, Long sourceId,
                           String taxPeriod) {}

    // ── Scan ─────────────────────────────────────────────────────────────────

    /**
     * Runs every detector and records what it finds.
     *
     * Re-running is safe: a finding already recorded for the same subject
     * updates that row rather than creating another, so the review queue does
     * not grow every time the scan runs. A finding that has already been
     * reviewed is left alone — reopening someone's decision is not the scan's
     * call to make.
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> scan() throws VeloriaException {
        List<Detected> found = new ArrayList<>();
        found.addAll(fromReconciliationSweep());
        found.addAll(invalidGstins());
        found.addAll(sellerStateContradictions());
        found.addAll(ledgerInconsistencies());
        found.addAll(invoiceInconsistencies());

        int created = 0, updated = 0, untouched = 0;
        for (Detected d : found) {
            Optional<GstAccountingExceptionEntity> existing =
                    exceptionRepo.findByOrganizationIdAndExceptionTypeAndSourceDocumentNumber(
                            orgId(), d.type(), d.reference());
            if (existing.isPresent()) {
                GstAccountingExceptionEntity e = existing.get();
                if (TERMINAL.contains(e.getStatus())) { untouched++; continue; }
                e.setErrorMessage(d.description());
                e.setSeverity(d.severity());
                e.setTaxPeriod(d.taxPeriod());
                exceptionRepo.save(e);
                updated++;
            } else {
                exceptionRepo.save(GstAccountingExceptionEntity.builder()
                        .operation("HISTORICAL_REVIEW")
                        .exceptionType(d.type())
                        .severity(d.severity())
                        .sourceType(d.sourceType())
                        .sourceId(d.sourceId())
                        .sourceDocumentNumber(d.reference())
                        .taxPeriod(d.taxPeriod())
                        .errorMessage(d.description())
                        .status(PENDING_REVIEW)
                        .organizationId(orgId())
                        .gstRegistrationId(regId())
                        .createdAt(Instant.now())
                        .build());
                created++;
            }
        }

        auditService.log("GST_EXCEPTION", null, "SCAN", "HISTORICAL_SCAN",
                null, securityContext.actor());

        log.info("Historical GST scan: {} finding(s) — {} new, {} updated, {} already reviewed",
                found.size(), created, updated, untouched);

        return Map.of("findings", found.size(), "created", created,
                "updated", updated, "alreadyReviewed", untouched);
    }

    // ── Detectors ────────────────────────────────────────────────────────────

    /** Findings the existing order/purchase sweep already knows how to produce. */
    @SuppressWarnings("unchecked")
    private List<Detected> fromReconciliationSweep() {
        List<Detected> out = new ArrayList<>();
        Map<String, Object> report = reconciliationService.report();
        Object raw = report.get("findings");
        if (!(raw instanceof List<?> list)) return out;

        for (Object o : list) {
            if (!(o instanceof GstReconciliationService.Finding f)) continue;
            out.add(new Detected(f.category(), normalizeSeverity(f.severity()), f.reference(),
                    f.detail(), "RECONCILIATION", null, null));
        }
        return out;
    }

    /** A stored GSTIN that does not survive structural validation. */
    private List<Detected> invalidGstins() {
        List<Detected> out = new ArrayList<>();
        registrationRepo.findAll().stream()
                .filter(r -> Objects.equals(r.getOrganizationId(), orgId()))
                .forEach(r -> {
                    String gstin = r.getGstin();
                    if (gstin != null && !gstin.isBlank() && !identityService.isValidGstin(gstin)) {
                        out.add(new Detected("INVALID_GSTIN", CRITICAL,
                                identityService.maskGstin(gstin),
                                "Registered GSTIN does not pass structural validation, so every "
                                + "supply classified against it is suspect",
                                "GST_REGISTRATION", r.getId(), null));
                    }
                });
        return out;
    }

    /**
     * A movement whose recorded seller state disagrees with the state encoded in
     * the GSTIN it was issued under. This is the defect class that made
     * inter-state classification wrong at source.
     */
    private List<Detected> sellerStateContradictions() {
        List<Detected> out = new ArrayList<>();
        for (var reg : registrationRepo.findAll()) {
            if (!Objects.equals(reg.getOrganizationId(), orgId())) continue;
            String gstin = reg.getGstin();
            if (gstin == null || gstin.length() < 2 || !identityService.isValidGstin(gstin)) continue;
            String fromGstin = gstin.substring(0, 2);
            String stored = reg.getStateCode();
            if (stored != null && !stored.isBlank() && !stored.equals(fromGstin)) {
                out.add(new Detected("SELLER_STATE_CONTRADICTION", CRITICAL,
                        identityService.maskGstin(gstin),
                        "Stored state code " + stored + " contradicts state " + fromGstin
                        + " encoded in the GSTIN — inter-state classification cannot be trusted",
                        "GST_REGISTRATION", reg.getId(), null));
            }
        }
        return out;
    }

    /** Ledger rows whose own tax heads do not add up, or contradict each other. */
    private List<Detected> ledgerInconsistencies() {
        List<Detected> out = new ArrayList<>();
        for (GstMovementLedgerEntity m : movementRepo.findAll()) {
            if (!Objects.equals(m.getOrganizationId(), orgId())) continue;
            if (!"POSTED".equals(m.getStatus())) continue;

            long c = nz(m.getCgstAmountPaise()), s = nz(m.getSgstAmountPaise()),
                 i = nz(m.getIgstAmountPaise()), total = nz(m.getTotalTaxPaise());

            if (c + s + i != total) {
                out.add(new Detected("LEDGER_TAX_SUM_MISMATCH", CRITICAL, m.getMovementNumber(),
                        "CGST + SGST + IGST (" + (c + s + i) + " paise) does not equal the recorded "
                        + "total of " + total + " paise",
                        "GST_MOVEMENT", m.getId(), m.getTaxPeriod()));
            }
            if (c != 0 && i != 0) {
                out.add(new Detected("LEDGER_HEAD_CONFLICT", CRITICAL, m.getMovementNumber(),
                        "Both CGST and IGST are non-zero on the same supply, so it is recorded as "
                        + "intra-state and inter-state at once",
                        "GST_MOVEMENT", m.getId(), m.getTaxPeriod()));
            }
            if (m.getTaxPeriod() == null || m.getTaxPeriod().isBlank()) {
                out.add(new Detected("LEDGER_WITHOUT_PERIOD", HIGH, m.getMovementNumber(),
                        "Movement is not assigned to a tax period, so it appears in no return",
                        "GST_MOVEMENT", m.getId(), null));
            }

            // The heads must agree with how the supply was classified. A row
            // labelled inter-state but carrying CGST/SGST was taxed under the
            // wrong heads, which is a different amount owed to a different
            // authority — not a rounding difference.
            String supply = m.getSupplyType();
            if ("INTER_STATE".equals(supply) && (c != 0 || s != 0)) {
                out.add(new Detected("SUPPLY_TYPE_HEAD_MISMATCH", CRITICAL, m.getMovementNumber(),
                        "Recorded as inter-state but carries CGST/SGST — the supply was taxed "
                        + "under the wrong heads",
                        "GST_MOVEMENT", m.getId(), m.getTaxPeriod()));
            }
            if ("INTRA_STATE".equals(supply) && i != 0) {
                out.add(new Detected("SUPPLY_TYPE_HEAD_MISMATCH", CRITICAL, m.getMovementNumber(),
                        "Recorded as intra-state but carries IGST — the supply was taxed under "
                        + "the wrong heads",
                        "GST_MOVEMENT", m.getId(), m.getTaxPeriod()));
            }
        }
        return out;
    }

    /** Issued invoices that are missing something a tax invoice must carry. */
    private List<Detected> invoiceInconsistencies() {
        List<Detected> out = new ArrayList<>();
        for (SalesInvoiceEntity inv : invoiceRepo.findAll()) {
            if (!Objects.equals(inv.getOrganizationId(), orgId())) continue;
            if ("DRAFT".equals(inv.getStatus())) continue;

            if (inv.getInvoiceNumber() == null || inv.getInvoiceNumber().isBlank()) {
                out.add(new Detected("INVOICE_WITHOUT_NUMBER", CRITICAL,
                        "invoice#" + inv.getId(),
                        "Invoice is " + inv.getStatus() + " but carries no invoice number",
                        "SALES_INVOICE", inv.getId(), inv.getTaxPeriod()));
            }
            if (inv.getPlaceOfSupply() == null || inv.getPlaceOfSupply().isBlank()) {
                out.add(new Detected("INVOICE_WITHOUT_PLACE_OF_SUPPLY", CRITICAL,
                        String.valueOf(inv.getInvoiceNumber()),
                        "Issued invoice has no place of supply, so its tax head cannot be verified",
                        "SALES_INVOICE", inv.getId(), inv.getTaxPeriod()));
            }
            if ("B2B".equalsIgnoreCase(inv.getCustomerType())
                    && (inv.getCustomerGstin() == null || inv.getCustomerGstin().isBlank())) {
                out.add(new Detected("B2B_INVOICE_WITHOUT_GSTIN", HIGH,
                        String.valueOf(inv.getInvoiceNumber()),
                        "Invoice is marked B2B but has no customer GSTIN, so it cannot be reported "
                        + "in the B2B section of GSTR-1",
                        "SALES_INVOICE", inv.getId(), inv.getTaxPeriod()));
            }
        }
        return out;
    }

    // ── Review ───────────────────────────────────────────────────────────────

    /**
     * Moves an exception through review. The transition is checked, so a
     * decision cannot be quietly overwritten once it has been made.
     */
    @Transactional(rollbackFor = Exception.class)
    public GstAccountingExceptionEntity review(Long id, String toStatus, String comments)
            throws VeloriaException {
        GstAccountingExceptionEntity e = exceptionRepo.findById(id)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "GST exception " + id + " was not found"));

        if (!Objects.equals(e.getOrganizationId(), orgId())) {
            throw new VeloriaException(ResponseCode.ACCESS_DENIED,
                    "This GST exception belongs to another organisation");
        }

        String from = e.getStatus() == null ? PENDING_REVIEW : e.getStatus();
        Set<String> allowed = ALLOWED.getOrDefault(from, Set.of());
        if (!allowed.contains(toStatus)) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A " + from + " exception cannot move to " + toStatus
                    + ". Allowed from here: " + (allowed.isEmpty() ? "none — it is already closed" : allowed));
        }
        if (TERMINAL.contains(toStatus) && (comments == null || comments.isBlank())) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Closing an exception as " + toStatus + " requires a comment recording why");
        }

        e.setStatus(toStatus);
        e.setReviewedBy(securityContext.actor());
        e.setReviewedAt(Instant.now());
        e.setReviewComments(comments);
        if (TERMINAL.contains(toStatus)) {
            e.setResolvedAt(Instant.now());
            e.setResolvedBy(securityContext.actor());
            e.setResolutionNotes(comments);
        }
        GstAccountingExceptionEntity saved = exceptionRepo.save(e);

        auditService.log("GST_EXCEPTION", id, e.getSourceDocumentNumber(),
                "EXCEPTION_" + toStatus, "status", from, toStatus,
                e.getTaxPeriod(), securityContext.actor());

        return saved;
    }

    // ── Dashboard ────────────────────────────────────────────────────────────

    /**
     * Counts open exceptions by type, severity and period.
     *
     * Derived from the persisted records rather than by re-running detection, so
     * the dashboard and the review queue can never disagree.
     */
    public Map<String, Object> dashboard(String taxPeriod) {
        List<GstAccountingExceptionEntity> all = exceptionRepo.findAll().stream()
                .filter(e -> Objects.equals(e.getOrganizationId(), orgId()))
                .filter(e -> taxPeriod == null || taxPeriod.isBlank()
                        || taxPeriod.equals(e.getTaxPeriod()))
                .toList();

        Map<String, Long> byType     = new TreeMap<>();
        Map<String, Long> bySeverity = new TreeMap<>();
        Map<String, Long> byStatus   = new TreeMap<>();
        for (GstAccountingExceptionEntity e : all) {
            byType.merge(e.getExceptionType() == null ? "RUNTIME_FAILURE" : e.getExceptionType(), 1L, Long::sum);
            bySeverity.merge(e.getSeverity() == null ? "UNCLASSIFIED" : e.getSeverity(), 1L, Long::sum);
            byStatus.merge(e.getStatus() == null ? PENDING_REVIEW : e.getStatus(), 1L, Long::sum);
        }

        long open = all.stream().filter(e -> !TERMINAL.contains(e.getStatus())).count();
        long critical = all.stream()
                .filter(e -> !TERMINAL.contains(e.getStatus()))
                .filter(e -> CRITICAL.equals(e.getSeverity())).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("open", open);
        out.put("openCritical", critical);
        out.put("byType", byType);
        out.put("bySeverity", bySeverity);
        out.put("byStatus", byStatus);
        out.put("taxPeriod", taxPeriod);
        return out;
    }

    /** Open exceptions, most severe first, for the review screen. */
    public List<GstAccountingExceptionEntity> queue(String status, String taxPeriod) {
        List<String> order = List.of(CRITICAL, HIGH, MEDIUM, LOW);
        return exceptionRepo.findAll().stream()
                .filter(e -> Objects.equals(e.getOrganizationId(), orgId()))
                .filter(e -> status == null || status.isBlank() || status.equals(e.getStatus()))
                .filter(e -> taxPeriod == null || taxPeriod.isBlank() || taxPeriod.equals(e.getTaxPeriod()))
                .sorted(Comparator
                        .comparingInt((GstAccountingExceptionEntity e) -> {
                            int i = order.indexOf(e.getSeverity());
                            return i < 0 ? order.size() : i;
                        })
                        .thenComparing(GstAccountingExceptionEntity::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private static long nz(Long v) { return v == null ? 0L : v; }

    private static String normalizeSeverity(String s) {
        if (s == null) return MEDIUM;
        return switch (s.toUpperCase()) {
            case "CRITICAL" -> CRITICAL;
            case "HIGH"     -> HIGH;
            case "LOW"      -> LOW;
            default         -> MEDIUM;
        };
    }
}
