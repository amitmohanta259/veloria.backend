package com.app.master.service.gst;

import com.app.master.service.core.entity.*;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.security.GstSecurityContext;
import com.app.master.service.repository.admin.*;
import com.app.master.service.service.admin.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Historical GST exceptions.
 *
 * Two rules under test: historical records are never rewritten automatically,
 * and a decision once made is not silently overwritten by a later scan.
 */
class GstHistoricalExceptionServiceTest {

    private GstAccountingExceptionRepository exceptionRepo;
    private GstReconciliationService reconciliation;
    private GstMovementLedgerRepository movementRepo;
    private SalesInvoiceRepository invoiceRepo;
    private GstRegistrationRepository registrationRepo;
    private GstIdentityService identity;
    private GstHistoricalExceptionService service;

    private final List<GstAccountingExceptionEntity> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        exceptionRepo = mock(GstAccountingExceptionRepository.class);
        reconciliation = mock(GstReconciliationService.class);
        movementRepo = mock(GstMovementLedgerRepository.class);
        invoiceRepo = mock(SalesInvoiceRepository.class);
        registrationRepo = mock(GstRegistrationRepository.class);
        identity = mock(GstIdentityService.class);
        GstAuditService audit = mock(GstAuditService.class);
        GstSecurityContext ctx = mock(GstSecurityContext.class);

        when(ctx.current()).thenReturn(Optional.empty());
        when(ctx.actor()).thenReturn("reviewer");
        when(identity.defaultOrganizationId()).thenReturn(1L);
        when(identity.primaryRegistration(any())).thenReturn(Optional.empty());
        when(identity.isValidGstin(anyString())).thenReturn(true);

        when(reconciliation.report()).thenReturn(Map.of("findings", List.of()));
        when(movementRepo.findAll()).thenReturn(List.of());
        when(invoiceRepo.findAll()).thenReturn(List.of());
        when(registrationRepo.findAll()).thenReturn(List.of());

        stored.clear();
        AtomicLong ids = new AtomicLong(1);
        when(exceptionRepo.save(any())).thenAnswer(i -> {
            GstAccountingExceptionEntity e = i.getArgument(0);
            if (e.getId() == null) { e.setId(ids.getAndIncrement()); stored.add(e); }
            return e;
        });
        when(exceptionRepo.findByOrganizationIdAndExceptionTypeAndSourceDocumentNumber(
                any(), anyString(), any())).thenReturn(Optional.empty());
        when(exceptionRepo.findAll()).thenAnswer(i -> new ArrayList<>(stored));

        service = new GstHistoricalExceptionService(exceptionRepo, reconciliation, movementRepo,
                invoiceRepo, registrationRepo, identity, audit, ctx);
    }

    private GstMovementLedgerEntity movement(String number, long c, long s, long i, long total) {
        return GstMovementLedgerEntity.builder()
                .id(1L).movementNumber(number).status("POSTED").organizationId(1L)
                .taxPeriod("2026-09")
                .cgstAmountPaise(c).sgstAmountPaise(s).igstAmountPaise(i).totalTaxPaise(total)
                .build();
    }

    // ── Detection ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A ledger row whose tax heads do not add up is raised as critical")
    void detectsTaxSumMismatch() throws Exception {
        when(movementRepo.findAll()).thenReturn(List.of(movement("M-1", 5000L, 5000L, 0L, 99999L)));

        Map<String, Object> result = service.scan();

        assertEquals(1, result.get("created"));
        GstAccountingExceptionEntity e = stored.get(0);
        assertEquals("LEDGER_TAX_SUM_MISMATCH", e.getExceptionType());
        assertEquals(GstHistoricalExceptionService.CRITICAL, e.getSeverity());
        assertEquals(GstHistoricalExceptionService.PENDING_REVIEW, e.getStatus());
        assertEquals("2026-09", e.getTaxPeriod());
    }

    @Test
    @DisplayName("A row carrying both CGST and IGST is raised")
    void detectsHeadConflict() throws Exception {
        when(movementRepo.findAll()).thenReturn(List.of(movement("M-2", 5000L, 0L, 5000L, 10000L)));

        service.scan();

        assertTrue(stored.stream().anyMatch(e -> "LEDGER_HEAD_CONFLICT".equals(e.getExceptionType())));
    }

    @Test
    @DisplayName("A movement with no tax period is raised — it would appear in no return")
    void detectsMovementWithoutPeriod() throws Exception {
        GstMovementLedgerEntity m = movement("M-3", 5000L, 5000L, 0L, 10000L);
        m.setTaxPeriod(null);
        when(movementRepo.findAll()).thenReturn(List.of(m));

        service.scan();

        assertTrue(stored.stream().anyMatch(e -> "LEDGER_WITHOUT_PERIOD".equals(e.getExceptionType())));
    }

    @Test
    @DisplayName("A row taxed under heads that contradict its supply type is raised as critical")
    void detectsSupplyTypeHeadMismatch() throws Exception {
        GstMovementLedgerEntity inter = movement("M-INT", 5000L, 5000L, 0L, 10000L);
        inter.setSupplyType("INTER_STATE");
        when(movementRepo.findAll()).thenReturn(List.of(inter));

        service.scan();

        var e = stored.stream()
                .filter(x -> "SUPPLY_TYPE_HEAD_MISMATCH".equals(x.getExceptionType()))
                .findFirst().orElseThrow();
        assertEquals(GstHistoricalExceptionService.CRITICAL, e.getSeverity());
        assertTrue(e.getErrorMessage().contains("wrong heads"));
    }

    @Test
    @DisplayName("An intra-state row carrying IGST is raised")
    void detectsIntraStateWithIgst() throws Exception {
        GstMovementLedgerEntity intra = movement("M-INTRA", 0L, 0L, 10000L, 10000L);
        intra.setSupplyType("INTRA_STATE");
        when(movementRepo.findAll()).thenReturn(List.of(intra));

        service.scan();

        assertTrue(stored.stream()
                .anyMatch(x -> "SUPPLY_TYPE_HEAD_MISMATCH".equals(x.getExceptionType())));
    }

    @Test
    @DisplayName("A correctly classified supply raises nothing")
    void correctlyClassifiedSupplyIsClean() throws Exception {
        GstMovementLedgerEntity ok = movement("M-OK", 5000L, 5000L, 0L, 10000L);
        ok.setSupplyType("INTRA_STATE");
        when(movementRepo.findAll()).thenReturn(List.of(ok));

        service.scan();

        assertTrue(stored.isEmpty());
    }

    @Test
    @DisplayName("A registration whose stored state contradicts its GSTIN is raised as critical")
    void detectsSellerStateContradiction() throws Exception {
        when(registrationRepo.findAll()).thenReturn(List.of(GstRegistrationEntity.builder()
                .id(1L).organizationId(1L).gstin("21AABCU9603R1ZX").stateCode("20").build()));

        service.scan();

        var found = stored.stream()
                .filter(e -> "SELLER_STATE_CONTRADICTION".equals(e.getExceptionType()))
                .findFirst().orElseThrow();
        assertEquals(GstHistoricalExceptionService.CRITICAL, found.getSeverity());
        assertTrue(found.getErrorMessage().contains("contradicts"));
    }

    @Test
    @DisplayName("An issued invoice with no place of supply is raised")
    void detectsInvoiceWithoutPlaceOfSupply() throws Exception {
        when(invoiceRepo.findAll()).thenReturn(List.of(SalesInvoiceEntity.builder()
                .id(1L).organizationId(1L).status("ISSUED").invoiceNumber("INV-1")
                .taxPeriod("2026-09").placeOfSupply(null).build()));

        service.scan();

        assertTrue(stored.stream()
                .anyMatch(e -> "INVOICE_WITHOUT_PLACE_OF_SUPPLY".equals(e.getExceptionType())));
    }

    @Test
    @DisplayName("A B2B invoice with no customer GSTIN is raised")
    void detectsB2bInvoiceWithoutGstin() throws Exception {
        when(invoiceRepo.findAll()).thenReturn(List.of(SalesInvoiceEntity.builder()
                .id(1L).organizationId(1L).status("ISSUED").invoiceNumber("INV-2")
                .placeOfSupply("27").customerType("B2B").customerGstin(null).build()));

        service.scan();

        assertTrue(stored.stream()
                .anyMatch(e -> "B2B_INVOICE_WITHOUT_GSTIN".equals(e.getExceptionType())));
    }

    @Test
    @DisplayName("A draft invoice is not judged as if it were issued")
    void draftInvoicesAreNotFlagged() throws Exception {
        when(invoiceRepo.findAll()).thenReturn(List.of(SalesInvoiceEntity.builder()
                .id(1L).organizationId(1L).status("DRAFT").invoiceNumber(null)
                .placeOfSupply(null).build()));

        service.scan();

        assertTrue(stored.isEmpty(), "a draft is not yet a tax invoice");
    }

    @Test
    @DisplayName("Another organisation's data is never scanned")
    void otherOrganisationsAreIgnored() throws Exception {
        when(movementRepo.findAll()).thenReturn(List.of(GstMovementLedgerEntity.builder()
                .id(1L).movementNumber("OTHER").status("POSTED").organizationId(999L)
                .cgstAmountPaise(1L).sgstAmountPaise(1L).igstAmountPaise(1L).totalTaxPaise(999L)
                .build()));

        service.scan();

        assertTrue(stored.isEmpty());
    }

    @Test
    @DisplayName("Findings from the existing reconciliation sweep are carried through, not restated")
    void reusesReconciliationFindings() throws Exception {
        when(reconciliation.report()).thenReturn(Map.of("findings", List.of(
                new GstReconciliationService.Finding("MISSING_PLACE_OF_SUPPLY", "VO-1",
                        "no place of supply", "HIGH"))));

        service.scan();

        var e = stored.stream()
                .filter(x -> "MISSING_PLACE_OF_SUPPLY".equals(x.getExceptionType()))
                .findFirst().orElseThrow();
        assertEquals(GstHistoricalExceptionService.HIGH, e.getSeverity());
        assertEquals("VO-1", e.getSourceDocumentNumber());
    }

    // ── Idempotency ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Rescanning updates the existing exception instead of stacking duplicates")
    void rescanUpdatesRatherThanDuplicates() throws Exception {
        GstAccountingExceptionEntity existing = GstAccountingExceptionEntity.builder()
                .id(50L).organizationId(1L).exceptionType("LEDGER_TAX_SUM_MISMATCH")
                .sourceDocumentNumber("M-1").status(GstHistoricalExceptionService.PENDING_REVIEW)
                .build();
        when(exceptionRepo.findByOrganizationIdAndExceptionTypeAndSourceDocumentNumber(
                any(), eq("LEDGER_TAX_SUM_MISMATCH"), eq("M-1"))).thenReturn(Optional.of(existing));
        when(movementRepo.findAll()).thenReturn(List.of(movement("M-1", 5000L, 5000L, 0L, 99999L)));

        Map<String, Object> result = service.scan();

        assertEquals(0, result.get("created"));
        assertEquals(1, result.get("updated"));
    }

    @Test
    @DisplayName("A scan does not reopen an exception someone has already decided")
    void rescanLeavesReviewedExceptionsAlone() throws Exception {
        GstAccountingExceptionEntity closed = GstAccountingExceptionEntity.builder()
                .id(51L).organizationId(1L).exceptionType("LEDGER_TAX_SUM_MISMATCH")
                .sourceDocumentNumber("M-1").status(GstHistoricalExceptionService.APPROVED)
                .build();
        when(exceptionRepo.findByOrganizationIdAndExceptionTypeAndSourceDocumentNumber(
                any(), anyString(), any())).thenReturn(Optional.of(closed));
        when(movementRepo.findAll()).thenReturn(List.of(movement("M-1", 5000L, 5000L, 0L, 99999L)));

        Map<String, Object> result = service.scan();

        assertEquals(1, result.get("alreadyReviewed"));
        assertEquals(GstHistoricalExceptionService.APPROVED, closed.getStatus());
    }

    // ── Review ───────────────────────────────────────────────────────────────

    private GstAccountingExceptionEntity open() {
        GstAccountingExceptionEntity e = GstAccountingExceptionEntity.builder()
                .id(9L).organizationId(1L).exceptionType("LEDGER_HEAD_CONFLICT")
                .sourceDocumentNumber("M-9").taxPeriod("2026-09")
                .status(GstHistoricalExceptionService.PENDING_REVIEW)
                .createdAt(Instant.now()).build();
        when(exceptionRepo.findById(9L)).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    @DisplayName("Review records who decided, when, and why")
    void reviewRecordsTheDecision() throws Exception {
        open();

        var reviewed = service.review(9L, GstHistoricalExceptionService.CORRECTED,
                "Corrected by credit note CN-4");

        assertEquals(GstHistoricalExceptionService.CORRECTED, reviewed.getStatus());
        assertEquals("reviewer", reviewed.getReviewedBy());
        assertNotNull(reviewed.getReviewedAt());
        assertEquals("Corrected by credit note CN-4", reviewed.getReviewComments());
    }

    @Test
    @DisplayName("Closing an exception without a comment is refused")
    void closingRequiresAComment() {
        open();

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> service.review(9L, GstHistoricalExceptionService.APPROVED, "  "));
        assertTrue(e.getMessage().contains("requires a comment"));
    }

    @Test
    @DisplayName("A closed exception cannot be silently reopened")
    void closedExceptionCannotBeReopened() {
        GstAccountingExceptionEntity closed = open();
        closed.setStatus(GstHistoricalExceptionService.REJECTED);

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> service.review(9L, GstHistoricalExceptionService.APPROVED, "changed my mind"));
        assertTrue(e.getMessage().contains("already closed"));
    }

    @Test
    @DisplayName("An exception belonging to another organisation cannot be reviewed")
    void crossTenantReviewIsRefused() {
        GstAccountingExceptionEntity other = open();
        other.setOrganizationId(999L);

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> service.review(9L, GstHistoricalExceptionService.APPROVED, "ok"));
        assertTrue(e.getMessage().contains("another organisation"));
    }

    @Test
    @DisplayName("An unknown exception id is reported, not silently ignored")
    void unknownExceptionIsReported() {
        when(exceptionRepo.findById(404L)).thenReturn(Optional.empty());

        assertThrows(VeloriaException.class,
                () -> service.review(404L, GstHistoricalExceptionService.APPROVED, "ok"));
    }

    // ── Dashboard ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The dashboard counts open and critical exceptions from the stored records")
    void dashboardAggregates() throws Exception {
        when(movementRepo.findAll()).thenReturn(List.of(
                movement("M-1", 5000L, 5000L, 0L, 99999L),
                movement("M-2", 5000L, 0L, 5000L, 10000L)));

        service.scan();
        Map<String, Object> d = service.dashboard(null);

        assertEquals(2, d.get("total"));
        assertEquals(2L, d.get("open"));
        assertEquals(2L, d.get("openCritical"));
        @SuppressWarnings("unchecked")
        Map<String, Long> byType = (Map<String, Long>) d.get("byType");
        assertTrue(byType.containsKey("LEDGER_TAX_SUM_MISMATCH"));
        assertTrue(byType.containsKey("LEDGER_HEAD_CONFLICT"));
    }

    @Test
    @DisplayName("The dashboard can be scoped to one tax period")
    void dashboardFiltersByPeriod() throws Exception {
        when(movementRepo.findAll()).thenReturn(List.of(movement("M-1", 5000L, 5000L, 0L, 99999L)));
        service.scan();

        assertEquals(1, service.dashboard("2026-09").get("total"));
        assertEquals(0, service.dashboard("2026-01").get("total"));
    }

    @Test
    @DisplayName("The review queue puts the most severe exceptions first")
    void queueOrdersBySeverity() throws Exception {
        stored.add(GstAccountingExceptionEntity.builder()
                .id(1L).organizationId(1L).severity(GstHistoricalExceptionService.LOW)
                .status(GstHistoricalExceptionService.PENDING_REVIEW).createdAt(Instant.now()).build());
        stored.add(GstAccountingExceptionEntity.builder()
                .id(2L).organizationId(1L).severity(GstHistoricalExceptionService.CRITICAL)
                .status(GstHistoricalExceptionService.PENDING_REVIEW).createdAt(Instant.now()).build());

        var queue = service.queue(null, null);

        assertEquals(GstHistoricalExceptionService.CRITICAL, queue.get(0).getSeverity());
    }
}
