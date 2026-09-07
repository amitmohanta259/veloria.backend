package com.app.master.service.gst;

import com.app.master.service.core.entity.GstInputTaxEntity;
import com.app.master.service.core.entity.GstItcTransactionEntity;
import com.app.master.service.core.entity.GstMovementLedgerEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.security.GstSecurityContext;
import com.app.master.service.repository.admin.GstInputTaxRepository;
import com.app.master.service.repository.admin.GstItcTransactionRepository;
import com.app.master.service.repository.admin.GstMovementLedgerRepository;
import com.app.master.service.service.admin.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static com.app.master.service.service.admin.GstItcService.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ITC lifecycle (spec sections 6 to 8, 11, 12) and the invariants from
 * section 60: claimed <= eligible, reversed <= claimed, reclaimed <= reversed.
 */
class GstItcServiceTest {

    private GstInputTaxRepository inputTaxRepo;
    private GstItcTransactionRepository txnRepo;
    private GstMovementLedgerRepository movementRepo;
    private GstTaxPeriodService periodService;
    private GstAuditService auditService;
    private GstItcService service;

    private GstInputTaxEntity invoice;
    private final List<GstItcTransactionEntity> saved = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        inputTaxRepo = mock(GstInputTaxRepository.class);
        txnRepo = mock(GstItcTransactionRepository.class);
        movementRepo = mock(GstMovementLedgerRepository.class);
        periodService = mock(GstTaxPeriodService.class);
        auditService = mock(GstAuditService.class);
        GstIdentityService identity = mock(GstIdentityService.class);
        GstSecurityContext ctx = mock(GstSecurityContext.class);

        when(ctx.current()).thenReturn(Optional.empty());
        when(ctx.actor()).thenReturn("tester");
        when(identity.defaultOrganizationId()).thenReturn(1L);
        when(identity.primaryRegistration(any())).thenReturn(Optional.empty());
        when(identity.businessGstin()).thenReturn("21AABCU9603R1ZX");
        doNothing().when(periodService).assertOpen(anyString());

        service = new GstItcService(inputTaxRepo, txnRepo, movementRepo, identity,
                auditService, periodService, ctx);

        // Vendor invoice: ₹500 CGST + ₹500 SGST charged.
        invoice = GstInputTaxEntity.builder()
                .id(7L).invoiceNumber("INV-1").vendorGstin("21AAAAA0000A1Z5")
                .taxPeriod("2026-09")
                .cgstAmount(50000L).sgstAmount(50000L).igstAmount(0L)
                .totalInputTax(100000L)
                .itcEligibility(PENDING_REVIEW).itcStatus(PENDING_REVIEW)
                .eligibleCgst(0L).eligibleSgst(0L).eligibleIgst(0L)
                .claimedCgst(0L).claimedSgst(0L).claimedIgst(0L)
                .reversedCgst(0L).reversedSgst(0L).reversedIgst(0L)
                .reclaimedCgst(0L).reclaimedSgst(0L).reclaimedIgst(0L)
                .organizationId(1L)
                .build();

        when(inputTaxRepo.findById(7L)).thenReturn(Optional.of(invoice));
        when(inputTaxRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(txnRepo.existsByTransactionReference(anyString())).thenReturn(false);
        when(txnRepo.findByInputTaxIdOrderByIdAsc(anyLong())).thenAnswer(i -> new ArrayList<>(saved));

        AtomicLong ids = new AtomicLong(100);
        when(txnRepo.save(any())).thenAnswer(i -> {
            GstItcTransactionEntity t = i.getArgument(0);
            if (t.getId() == null) t.setId(ids.incrementAndGet());
            saved.add(t);
            return t;
        });
        AtomicLong mids = new AtomicLong(500);
        when(movementRepo.save(any())).thenAnswer(i -> {
            GstMovementLedgerEntity m = i.getArgument(0);
            if (m.getId() == null) m.setId(mids.incrementAndGet());
            return m;
        });
    }

    private void makeEligible() throws Exception {
        service.setEligibility(7L, ELIGIBLE, "VALID_DOCUMENT", null, null);
    }

    @Test
    @DisplayName("Vendor GST is not automatically claimable")
    void notAutomaticallyEligible() {
        assertEquals(PENDING_REVIEW, invoice.getItcEligibility());
        assertEquals(0L, invoice.getEligibleCgst());
        VeloriaException ex = assertThrows(VeloriaException.class, () -> service.claim(7L, null));
        assertTrue(ex.getMessage().contains("PENDING_REVIEW"), ex.getMessage());
    }

    @Test
    @DisplayName("Marking eligible sets the full tax as eligible ITC")
    void eligibleSetsFullAmount() throws Exception {
        makeEligible();
        assertEquals(50000L, invoice.getEligibleCgst());
        assertEquals(50000L, invoice.getEligibleSgst());
        assertEquals(0L, invoice.getIneligibleCgst());
    }

    @Test
    @DisplayName("Marking ineligible leaves zero eligible and full ineligible")
    void ineligibleSetsZero() throws Exception {
        service.setEligibility(7L, INELIGIBLE, "BLOCKED_CREDIT", "Blocked category", null);
        assertEquals(0L, invoice.getEligibleCgst());
        assertEquals(50000L, invoice.getIneligibleCgst());
        assertEquals(INELIGIBLE, invoice.getItcEligibility());
    }

    @Test
    @DisplayName("Partial eligibility requires an explicit split")
    void partialRequiresSplit() {
        VeloriaException ex = assertThrows(VeloriaException.class,
                () -> service.setEligibility(7L, PARTIALLY_ELIGIBLE, "PARTIAL_BUSINESS_USE", null, null));
        assertTrue(ex.getMessage().contains("split"), ex.getMessage());
    }

    @Test
    @DisplayName("Partial eligibility cannot exceed the tax charged")
    void partialCannotExceedCharged() {
        assertThrows(VeloriaException.class, () -> service.setEligibility(7L, PARTIALLY_ELIGIBLE,
                "PARTIAL_BUSINESS_USE", null, new TaxHeads(60000L, 50000L, 0L)));
    }

    @Test
    @DisplayName("Partial eligibility records both eligible and ineligible halves")
    void partialSplitsBothWays() throws Exception {
        service.setEligibility(7L, PARTIALLY_ELIGIBLE, "PARTIAL_BUSINESS_USE", "60% business use",
                new TaxHeads(30000L, 30000L, 0L));
        assertEquals(30000L, invoice.getEligibleCgst());
        assertEquals(20000L, invoice.getIneligibleCgst());
    }

    @Test
    @DisplayName("An unknown reason code is rejected")
    void unknownReasonRejected() {
        assertThrows(VeloriaException.class,
                () -> service.setEligibility(7L, ELIGIBLE, "BECAUSE_I_SAID_SO", null, null));
    }

    @Test
    @DisplayName("Claiming posts a CLAIM transaction and a ledger movement")
    void claimPostsTransactionAndMovement() throws Exception {
        makeEligible();
        GstItcTransactionEntity txn = service.claim(7L, "September claim");

        assertEquals("CLAIM", txn.getTransactionType());
        assertEquals(100000L, txn.getTotalPaise());
        assertEquals(50000L, invoice.getClaimedCgst());
        assertEquals(CLAIMED, invoice.getItcStatus());
        verify(movementRepo, atLeastOnce()).save(any());
    }

    @Test
    @DisplayName("Invariant: claimed never exceeds eligible")
    void claimedNeverExceedsEligible() throws Exception {
        service.setEligibility(7L, PARTIALLY_ELIGIBLE, "PARTIAL_BUSINESS_USE", null,
                new TaxHeads(20000L, 20000L, 0L));
        service.claim(7L, null);

        assertEquals(20000L, invoice.getClaimedCgst());
        assertTrue(invoice.getClaimedCgst() <= invoice.getEligibleCgst());

        // Nothing further is claimable.
        assertThrows(VeloriaException.class, () -> service.claim(7L, null));
    }

    @Test
    @DisplayName("Claiming twice is idempotent on the transaction reference")
    void claimIsIdempotent() throws Exception {
        makeEligible();
        GstItcTransactionEntity first = service.claim(7L, null);

        when(txnRepo.existsByTransactionReference("GST-ITC-CLAIM-7")).thenReturn(true);
        when(txnRepo.findByTransactionReference("GST-ITC-CLAIM-7")).thenReturn(Optional.of(first));

        assertEquals(first.getId(), service.claim(7L, null).getId());
    }

    @Test
    @DisplayName("Invariant: reversed never exceeds claimed")
    void reversedNeverExceedsClaimed() throws Exception {
        makeEligible();
        service.claim(7L, null);

        VeloriaException ex = assertThrows(VeloriaException.class,
                () -> service.reverse(7L, new TaxHeads(60000L, 50000L, 0L), "RETURNED_GOODS", null));
        assertTrue(ex.getMessage().contains("more ITC than was claimed"), ex.getMessage());
    }

    @Test
    @DisplayName("Partial reversal moves only the requested amount")
    void partialReversal() throws Exception {
        makeEligible();
        service.claim(7L, null);
        service.reverse(7L, new TaxHeads(20000L, 20000L, 0L), "RETURNED_GOODS", "Part returned");

        assertEquals(20000L, invoice.getReversedCgst());
        assertEquals(REVERSED, invoice.getItcStatus());
        assertEquals(30000L, invoice.netClaimedCgst(), "50000 claimed less 20000 reversed");
    }

    @Test
    @DisplayName("Full reversal defaults to everything outstanding")
    void fullReversalDefaults() throws Exception {
        makeEligible();
        service.claim(7L, null);
        service.reverse(7L, null, "CREDIT_NOTE_RECEIVED", null);

        assertEquals(50000L, invoice.getReversedCgst());
        assertEquals(0L, invoice.netClaimedCgst());
    }

    @Test
    @DisplayName("A reversal requires a reason code")
    void reversalRequiresReason() throws Exception {
        makeEligible();
        service.claim(7L, null);
        assertThrows(VeloriaException.class, () -> service.reverse(7L, null, null, null));
    }

    @Test
    @DisplayName("Invariant: reclaimed never exceeds reversed")
    void reclaimedNeverExceedsReversed() throws Exception {
        makeEligible();
        service.claim(7L, null);
        service.reverse(7L, new TaxHeads(20000L, 20000L, 0L), "RETURNED_GOODS", null);

        VeloriaException ex = assertThrows(VeloriaException.class,
                () -> service.reclaim(7L, new TaxHeads(30000L, 20000L, 0L), "VALID_DOCUMENT", null));
        assertTrue(ex.getMessage().contains("more ITC than was reversed"), ex.getMessage());
    }

    @Test
    @DisplayName("Reclaim restores previously reversed credit")
    void reclaimRestoresCredit() throws Exception {
        makeEligible();
        service.claim(7L, null);
        service.reverse(7L, new TaxHeads(20000L, 20000L, 0L), "NOT_REFLECTED_IN_2B", null);
        service.reclaim(7L, new TaxHeads(20000L, 20000L, 0L), "VALID_DOCUMENT", "Now in 2B");

        assertEquals(20000L, invoice.getReclaimedCgst());
        assertEquals(RECLAIMED, invoice.getItcStatus());
        assertEquals(50000L, invoice.netClaimedCgst(), "Claim minus reversal plus reclaim");
    }

    @Test
    @DisplayName("Nothing can be reclaimed when nothing was reversed")
    void cannotReclaimWithoutReversal() throws Exception {
        makeEligible();
        service.claim(7L, null);
        assertThrows(VeloriaException.class,
                () -> service.reclaim(7L, null, "VALID_DOCUMENT", null));
    }

    @Test
    @DisplayName("Eligibility cannot change once credit has been claimed")
    void eligibilityLockedAfterClaim() throws Exception {
        makeEligible();
        service.claim(7L, null);
        VeloriaException ex = assertThrows(VeloriaException.class,
                () -> service.setEligibility(7L, INELIGIBLE, "BLOCKED_CREDIT", null, null));
        assertTrue(ex.getMessage().contains("already been claimed"), ex.getMessage());
    }

    @Test
    @DisplayName("A locked period refuses ITC operations")
    void lockedPeriodRefusesItc() throws Exception {
        doThrow(new VeloriaException(com.app.master.service.core.response.ResponseCode.CONFLICT,
                "GST period 2026-09 is LOCKED"))
                .when(periodService).assertOpen(anyString());

        assertThrows(VeloriaException.class,
                () -> service.setEligibility(7L, ELIGIBLE, "VALID_DOCUMENT", null, null));
    }

    @Test
    @DisplayName("Every ITC transaction carries a unique reference")
    void transactionsHaveUniqueReferences() throws Exception {
        makeEligible();
        service.claim(7L, null);
        service.reverse(7L, new TaxHeads(10000L, 10000L, 0L), "RETURNED_GOODS", null);

        long distinct = saved.stream().map(GstItcTransactionEntity::getTransactionReference).distinct().count();
        assertEquals(saved.size(), distinct, "References must be unique to prevent double posting");
    }
}
