package com.app.master.service.gst;

import com.app.master.service.core.entity.GstCreditNoteEntity;
import com.app.master.service.core.entity.GstInputTaxEntity;
import com.app.master.service.core.entity.GstMovementLedgerEntity;
import com.app.master.service.core.entity.GstTaxPeriodEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.security.GstSecurityContext;
import com.app.master.service.repository.admin.*;
import com.app.master.service.service.admin.GstAuditService;
import com.app.master.service.service.admin.GstIdentityService;
import com.app.master.service.service.admin.GstTaxPeriodService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.app.master.service.service.admin.GstMovementService;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tax period totals, validation and locking (spec sections 20, 21, 29, 51, 52).
 */
class GstTaxPeriodServiceTest {

    private static final String PERIOD = "2026-09";

    private GstTaxPeriodRepository periodRepo;
    private GstMovementLedgerRepository movementRepo;
    private GstInputTaxRepository inputTaxRepo;
    private GstCreditNoteRepository creditNoteRepo;
    private GstIdentityService identity;
    private GstTaxPeriodService service;

    private GstTaxPeriodEntity period;

    @BeforeEach
    void setUp() {
        periodRepo = mock(GstTaxPeriodRepository.class);
        movementRepo = mock(GstMovementLedgerRepository.class);
        inputTaxRepo = mock(GstInputTaxRepository.class);
        creditNoteRepo = mock(GstCreditNoteRepository.class);
        identity = mock(GstIdentityService.class);
        GstAuditService audit = mock(GstAuditService.class);
        GstSecurityContext ctx = mock(GstSecurityContext.class);

        when(ctx.current()).thenReturn(Optional.empty());
        when(ctx.actor()).thenReturn("tester");
        when(identity.defaultOrganizationId()).thenReturn(1L);
        when(identity.primaryRegistration(any())).thenReturn(Optional.empty());
        when(identity.isValidGstin(anyString())).thenReturn(true);

        period = GstTaxPeriodEntity.builder()
                .id(1L).taxPeriod(PERIOD).financialYear("2026-27").status("OPEN")
                .organizationId(1L).build();

        when(periodRepo.findByOrganizationIdAndGstRegistrationIdAndTaxPeriod(any(), any(), anyString()))
                .thenReturn(Optional.of(period));
        when(periodRepo.findFirstByOrganizationIdAndTaxPeriod(any(), anyString()))
                .thenReturn(Optional.of(period));
        when(periodRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        // Output ₹1,000 tax on ₹20,000 taxable; input ₹500 on ₹10,000.
        when(movementRepo.sumByDirectionAndPeriod(any(), eq("OUT"), anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{50000L, 50000L, 0L, 100000L, 2000000L}));
        when(movementRepo.sumByDirectionAndPeriod(any(), eq("IN"), anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{25000L, 25000L, 0L, 50000L, 1000000L}));
        when(movementRepo.sumByMovementTypeAndPeriod(any(), anyString(), anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{0L, 0L, 0L, 0L, 0L}));
        when(movementRepo.findByTaxPeriodOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        when(inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        when(creditNoteRepo.findByTaxPeriodOrderByCreatedAtDesc(anyString())).thenReturn(List.of());

        service = new GstTaxPeriodService(periodRepo, movementRepo, inputTaxRepo, creditNoteRepo,
                identity, audit, ctx);
    }

    private GstMovementLedgerEntity movement(long c, long s, long i, long total) {
        return GstMovementLedgerEntity.builder()
                .id(1L).movementNumber("M-1").status("POSTED").taxPeriod(PERIOD)
                .direction(GstMovementService.DIR_OUT)
                .organizationId(1L).placeOfSupply("21").hsnCode("6204")
                .cgstAmountPaise(c).sgstAmountPaise(s).igstAmountPaise(i).totalTaxPaise(total)
                .build();
    }

    // ── Computation ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Totals are derived from the ledger, not from orders")
    void totalsComeFromLedger() {
        GstTaxPeriodEntity p = service.recompute(PERIOD);

        assertEquals(50000L, p.getOutputCgst());
        assertEquals(100000L, p.getTotalOutputTax());
        assertEquals(2000000L, p.getTotalOutwardTaxableValue());
        assertEquals(50000L, p.getTotalInputTax());
    }

    @Test
    @DisplayName("Only ELIGIBLE credit counts toward ITC, not all vendor GST")
    void onlyEligibleItcCounts() {
        when(inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD)).thenReturn(List.of(
                GstInputTaxEntity.builder().id(1L).organizationId(1L)
                        .cgstAmount(25000L).sgstAmount(25000L)
                        .eligibleCgst(25000L).eligibleSgst(25000L).eligibleIgst(0L).build(),
                GstInputTaxEntity.builder().id(2L).organizationId(1L)
                        .cgstAmount(10000L).sgstAmount(10000L)
                        .eligibleCgst(0L).eligibleSgst(0L).eligibleIgst(0L).build()));

        GstTaxPeriodEntity p = service.recompute(PERIOD);

        assertEquals(25000L, p.getEligibleCgstItc(), "The ineligible invoice must not contribute");
    }

    @Test
    @DisplayName("Cash payable is output minus eligible ITC, per tax head")
    void cashPayablePerHead() {
        when(inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD)).thenReturn(List.of(
                GstInputTaxEntity.builder().id(1L).organizationId(1L)
                        .eligibleCgst(20000L).eligibleSgst(20000L).eligibleIgst(0L).build()));

        GstTaxPeriodEntity p = service.recompute(PERIOD);

        assertEquals(30000L, p.getCashCgstPayable(), "50000 output less 20000 credit");
        assertEquals(30000L, p.getCashSgstPayable());
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("CGST and IGST on the same supply is a blocking error")
    void cgstIgstConflictIsError() {
        when(movementRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD))
                .thenReturn(List.of(movement(5000L, 0L, 5000L, 10000L)));

        var findings = service.validate(PERIOD);
        assertTrue(findings.stream().anyMatch(f ->
                "CGST_IGST_CONFLICT".equals(f.code()) && "ERROR".equals(f.severity())));
    }

    @Test
    @DisplayName("Tax heads that do not sum to the total are a blocking error")
    void taxSumMismatchIsError() {
        when(movementRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD))
                .thenReturn(List.of(movement(5000L, 5000L, 0L, 99999L)));

        assertTrue(service.validate(PERIOD).stream().anyMatch(f ->
                "TAX_SUM_MISMATCH".equals(f.code()) && "ERROR".equals(f.severity())));
    }

    @Test
    @DisplayName("A missing place of supply blocks filing")
    void missingPlaceOfSupplyIsError() {
        GstMovementLedgerEntity m = movement(5000L, 5000L, 0L, 10000L);
        m.setPlaceOfSupply(null);
        when(movementRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD)).thenReturn(List.of(m));

        assertTrue(service.validate(PERIOD).stream().anyMatch(f ->
                "MISSING_PLACE_OF_SUPPLY".equals(f.code()) && "ERROR".equals(f.severity())));
    }

    @Test
    @DisplayName("A missing HSN is a warning, not a blocker")
    void missingHsnIsWarning() {
        GstMovementLedgerEntity m = movement(5000L, 5000L, 0L, 10000L);
        m.setHsnCode(null);
        when(movementRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD)).thenReturn(List.of(m));

        var findings = service.validate(PERIOD);
        assertTrue(findings.stream().anyMatch(f ->
                "MISSING_HSN".equals(f.code()) && "WARNING".equals(f.severity())));
        assertTrue(findings.stream().noneMatch(f -> "ERROR".equals(f.severity())));
    }

    @Test
    @DisplayName("An ITC movement is not faulted for having no place of supply or HSN")
    void itcMovementIsExemptFromOutwardSupplyChecks() {
        GstMovementLedgerEntity m = movement(0L, 0L, 9000L, 9000L);
        m.setDirection(GstMovementService.DIR_ITC);
        m.setMovementNumber("GST-ITC-REV-1-2");
        m.setPlaceOfSupply(null);
        m.setHsnCode(null);
        when(movementRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD)).thenReturn(List.of(m));

        var findings = service.validate(PERIOD);
        assertTrue(findings.stream().noneMatch(f -> "MISSING_PLACE_OF_SUPPLY".equals(f.code())),
                "an ITC credit-ledger entry has no supply, so no place of supply to report");
        assertTrue(findings.stream().noneMatch(f -> "MISSING_HSN".equals(f.code())));
    }

    @Test
    @DisplayName("A purchase movement is not faulted for having no place of supply")
    void inwardMovementIsExemptFromOutwardSupplyChecks() {
        GstMovementLedgerEntity m = movement(4500L, 4500L, 0L, 9000L);
        m.setDirection(GstMovementService.DIR_IN);
        m.setPlaceOfSupply(null);
        when(movementRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD)).thenReturn(List.of(m));

        assertTrue(service.validate(PERIOD).stream()
                .noneMatch(f -> "MISSING_PLACE_OF_SUPPLY".equals(f.code())));
    }

    @Test
    @DisplayName("A credit note still needs a place of supply — it reports in GSTR-1")
    void adjustmentMovementStillNeedsPlaceOfSupply() {
        GstMovementLedgerEntity m = movement(2500L, 2500L, 0L, 5000L);
        m.setDirection(GstMovementService.DIR_ADJUSTMENT);
        m.setPlaceOfSupply(null);
        when(movementRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD)).thenReturn(List.of(m));

        assertTrue(service.validate(PERIOD).stream().anyMatch(f ->
                "MISSING_PLACE_OF_SUPPLY".equals(f.code()) && "ERROR".equals(f.severity())));
    }

    @Test
    @DisplayName("A vendor invoice without a GSTIN blocks filing")
    void missingVendorGstinIsError() {
        when(inputTaxRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD)).thenReturn(List.of(
                GstInputTaxEntity.builder().id(1L).organizationId(1L)
                        .invoiceNumber("INV-1").vendorGstin(null).build()));

        assertTrue(service.validate(PERIOD).stream().anyMatch(f ->
                "MISSING_VENDOR_GSTIN".equals(f.code()) && "ERROR".equals(f.severity())));
    }

    @Test
    @DisplayName("A credit note without an original invoice blocks filing")
    void creditNoteWithoutInvoiceIsError() {
        when(creditNoteRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD)).thenReturn(List.of(
                GstCreditNoteEntity.builder().id(1L).organizationId(1L)
                        .creditNoteNumber("CN-1").originalOrderCode(null).status("ISSUED").build()));

        assertTrue(service.validate(PERIOD).stream().anyMatch(f ->
                "CREDIT_NOTE_WITHOUT_INVOICE".equals(f.code()) && "ERROR".equals(f.severity())));
    }

    @Test
    @DisplayName("The validation report classifies findings and gates readiness")
    void reportGatesReadiness() {
        when(movementRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD))
                .thenReturn(List.of(movement(5000L, 0L, 5000L, 10000L)));

        var report = service.validationReport(PERIOD);
        assertEquals(false, report.get("readyForFiling"));
        assertTrue((long) report.get("errorCount") > 0);
    }

    // ── Locking ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Blocking errors prevent marking a period ready for filing")
    void errorsBlockReadyForFiling() {
        when(movementRepo.findByTaxPeriodOrderByCreatedAtDesc(PERIOD))
                .thenReturn(List.of(movement(5000L, 0L, 5000L, 10000L)));

        VeloriaException ex = assertThrows(VeloriaException.class,
                () -> service.markReadyForFiling(PERIOD));
        assertTrue(ex.getMessage().contains("blocking validation error"), ex.getMessage());
    }

    @Test
    @DisplayName("A clean period can be marked ready for filing")
    void cleanPeriodBecomesReady() throws Exception {
        assertEquals("READY_FOR_FILING", service.markReadyForFiling(PERIOD).getStatus());
    }

    @Test
    @DisplayName("Locking records who locked it and when")
    void lockRecordsActor() throws Exception {
        GstTaxPeriodEntity p = service.lock(PERIOD);

        assertEquals("LOCKED", p.getStatus());
        assertEquals("tester", p.getLockedBy());
        assertNotNull(p.getLockedAt());
        assertTrue(p.isLocked());
    }

    @Test
    @DisplayName("A locked period refuses new accounting entries")
    void lockedPeriodRefusesEntries() throws Exception {
        service.lock(PERIOD);

        VeloriaException ex = assertThrows(VeloriaException.class, () -> service.assertOpen(PERIOD));
        assertTrue(ex.getMessage().contains("LOCKED"), ex.getMessage());
        assertTrue(ex.getMessage().contains("credit note"),
                "The error should point at the correct remedy");
    }

    @Test
    @DisplayName("Locking twice is refused")
    void cannotLockTwice() throws Exception {
        service.lock(PERIOD);
        assertThrows(VeloriaException.class, () -> service.lock(PERIOD));
    }

    @Test
    @DisplayName("Unlocking requires a reason")
    void unlockRequiresReason() throws Exception {
        service.lock(PERIOD);
        assertThrows(VeloriaException.class, () -> service.unlock(PERIOD, null));
        assertThrows(VeloriaException.class, () -> service.unlock(PERIOD, "  "));
    }

    @Test
    @DisplayName("Unlocking is recorded with actor and reason")
    void unlockIsAudited() throws Exception {
        service.lock(PERIOD);
        GstTaxPeriodEntity p = service.unlock(PERIOD, "Correction approved by CA");

        assertEquals("UNDER_REVIEW", p.getStatus());
        assertEquals("tester", p.getUnlockedBy());
        assertEquals("Correction approved by CA", p.getUnlockReason());
        assertNotNull(p.getUnlockedAt());
    }

    @Test
    @DisplayName("An open period cannot be unlocked")
    void cannotUnlockOpenPeriod() {
        assertThrows(VeloriaException.class, () -> service.unlock(PERIOD, "no reason to"));
    }

    @Test
    @DisplayName("A FILED period is treated as locked")
    void filedIsLocked() {
        period.setStatus("FILED");
        assertTrue(period.isLocked());
        assertThrows(VeloriaException.class, () -> service.assertOpen(PERIOD));
    }
}
