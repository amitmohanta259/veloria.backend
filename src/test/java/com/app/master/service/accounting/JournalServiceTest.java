package com.app.master.service.accounting;

import com.app.master.service.core.entity.*;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.repository.admin.*;
import com.app.master.service.service.admin.GstAuditService;
import com.app.master.service.service.admin.JournalService;
import com.app.master.service.service.admin.JournalService.Draft;
import com.app.master.service.service.admin.JournalService.Posting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The double-entry rules.
 *
 * The governing rule: an unbalanced entry is refused, never adjusted to fit. A
 * ledger that accepts one has stopped being a ledger.
 */
class JournalServiceTest {

    private JournalEntryRepository journalRepo;
    private JournalEntryLineRepository lineRepo;
    private ChartOfAccountRepository accountRepo;
    private AccountingPeriodRepository periodRepo;
    private JournalService service;

    private final List<JournalEntryLineEntity> savedLines = new ArrayList<>();

    @BeforeEach
    void setUp() {
        journalRepo = mock(JournalEntryRepository.class);
        lineRepo = mock(JournalEntryLineRepository.class);
        accountRepo = mock(ChartOfAccountRepository.class);
        periodRepo = mock(AccountingPeriodRepository.class);
        GstAuditService audit = mock(GstAuditService.class);

        // Every account the tests post to exists in the chart.
        when(accountRepo.findByCode(anyString())).thenAnswer(i ->
                Optional.of(ChartOfAccountEntity.builder()
                        .code(i.getArgument(0)).name("Account").accountType("ASSET")
                        .normalBalance("DEBIT").build()));
        when(periodRepo.findByPeriod(anyString())).thenReturn(Optional.empty());
        when(journalRepo.findBySourceTypeAndSourceIdAndStatus(any(), any(), any()))
                .thenReturn(Optional.empty());

        savedLines.clear();
        AtomicLong ids = new AtomicLong(100);
        when(journalRepo.save(any())).thenAnswer(i -> {
            JournalEntryEntity e = i.getArgument(0);
            if (e.getId() == null) e.setId(ids.incrementAndGet());
            return e;
        });
        when(lineRepo.saveAll(any())).thenAnswer(i -> {
            List<JournalEntryLineEntity> l = i.getArgument(0);
            savedLines.addAll(l);
            return l;
        });

        service = new JournalService(journalRepo, lineRepo, accountRepo, periodRepo, audit);
    }

    private Draft draft(List<Posting> postings) {
        return new Draft(LocalDate.of(2026, 8, 15), "REF-1", "TEST", 1L, "Test entry", postings);
    }

    // ── Balance ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A balanced entry posts, with debits and credits recorded")
    void balancedEntryPosts() throws Exception {
        JournalEntryEntity e = service.post(draft(List.of(
                Posting.debit("1100", 10000L, "receivable"),
                Posting.credit("4010", 10000L, "sales"))));

        assertEquals(10000L, e.getTotalDebitPaise());
        assertEquals(10000L, e.getTotalCreditPaise());
        assertEquals("2026-08", e.getPeriod());
        assertEquals(2, savedLines.size());
    }

    @Test
    @DisplayName("An unbalanced entry is refused, and the difference is named")
    void unbalancedEntryIsRefused() {
        VeloriaException ex = assertThrows(VeloriaException.class, () -> service.post(draft(List.of(
                Posting.debit("1100", 10000L, "receivable"),
                Posting.credit("4010", 9000L, "sales")))));

        assertTrue(ex.getMessage().contains("does not balance"));
        assertTrue(ex.getMessage().contains("1000"), "the difference must be stated");
        assertTrue(savedLines.isEmpty(), "nothing may be written when the entry is refused");
    }

    @Test
    @DisplayName("A many-sided entry balances across all of its lines")
    void multiLineEntryBalances() throws Exception {
        JournalEntryEntity e = service.post(draft(List.of(
                Posting.debit("1100", 11800L, "gross"),
                Posting.credit("4010", 10000L, "net revenue"),
                Posting.credit("2100", 900L, "CGST"),
                Posting.credit("2110", 900L, "SGST"))));

        assertEquals(11800L, e.getTotalDebitPaise());
        assertEquals(11800L, e.getTotalCreditPaise());
        assertEquals(4, savedLines.size());
    }

    // ── Line shape ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("A line that is both a debit and a credit is refused")
    void twoSidedLineIsRefused() {
        VeloriaException ex = assertThrows(VeloriaException.class, () -> service.post(draft(List.of(
                new Posting("1100", 5000L, 5000L, "both sides"),
                Posting.credit("4010", 0L, "nothing")))));
        assertTrue(ex.getMessage().contains("either a debit or a credit"));
    }

    @Test
    @DisplayName("A negative posting is refused rather than flipped to the other side")
    void negativePostingIsRefused() {
        VeloriaException ex = assertThrows(VeloriaException.class, () -> service.post(draft(List.of(
                Posting.debit("1100", -5000L, "negative"),
                Posting.credit("4010", 5000L, "sales")))));
        assertTrue(ex.getMessage().contains("cannot be negative"));
    }

    @Test
    @DisplayName("An entry of zero is refused — it records nothing")
    void zeroEntryIsRefused() {
        assertThrows(VeloriaException.class, () -> service.post(draft(List.of(
                Posting.debit("1100", 0L, "zero"),
                Posting.credit("4010", 0L, "zero")))));
    }

    @Test
    @DisplayName("An entry with no postings is refused")
    void emptyEntryIsRefused() {
        assertThrows(VeloriaException.class, () -> service.post(draft(List.of())));
    }

    // ── Chart of accounts ────────────────────────────────────────────────────

    @Test
    @DisplayName("Posting to an account outside the chart is refused")
    void unknownAccountIsRefused() {
        when(accountRepo.findByCode("9999")).thenReturn(Optional.empty());

        VeloriaException ex = assertThrows(VeloriaException.class, () -> service.post(draft(List.of(
                Posting.debit("9999", 100L, "nowhere"),
                Posting.credit("4010", 100L, "sales")))));
        assertTrue(ex.getMessage().contains("not in the chart of accounts"));
    }

    // ── Idempotency ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Posting the same source twice returns the first entry, not a second")
    void postingIsIdempotentOnSource() throws Exception {
        JournalEntryEntity first = JournalEntryEntity.builder()
                .id(55L).journalNumber("JV-1").period("2026-08").build();
        when(journalRepo.findBySourceTypeAndSourceIdAndStatus("TEST", 1L, "POSTED"))
                .thenReturn(Optional.of(first));

        JournalEntryEntity again = service.post(draft(List.of(
                Posting.debit("1100", 10000L, "receivable"),
                Posting.credit("4010", 10000L, "sales"))));

        assertEquals(55L, again.getId(), "the existing entry is returned");
        assertTrue(savedLines.isEmpty(), "no second set of lines is written");
    }

    // ── Period lock ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("A closed period refuses new postings and points at the remedy")
    void closedPeriodRefusesPosting() {
        when(periodRepo.findByPeriod("2026-08")).thenReturn(Optional.of(
                AccountingPeriodEntity.builder().period("2026-08").status("CLOSED").build()));

        VeloriaException ex = assertThrows(VeloriaException.class, () -> service.post(draft(List.of(
                Posting.debit("1100", 10000L, "receivable"),
                Posting.credit("4010", 10000L, "sales")))));
        assertTrue(ex.getMessage().contains("CLOSED"));
        assertTrue(ex.getMessage().contains("reversal"), "the message must say what to do instead");
    }

    @Test
    @DisplayName("An open period accepts postings")
    void openPeriodAcceptsPosting() throws Exception {
        when(periodRepo.findByPeriod("2026-08")).thenReturn(Optional.of(
                AccountingPeriodEntity.builder().period("2026-08").status("OPEN").build()));

        assertNotNull(service.post(draft(List.of(
                Posting.debit("1100", 10000L, "receivable"),
                Posting.credit("4010", 10000L, "sales")))));
    }

    // ── Reversal ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A reversal mirrors the original and leaves it in place")
    void reversalMirrorsTheOriginal() throws Exception {
        JournalEntryEntity original = JournalEntryEntity.builder()
                .id(70L).journalNumber("JV-70").period("2026-08").status("POSTED")
                .reference("REF-1").build();
        when(journalRepo.findById(70L)).thenReturn(Optional.of(original));
        when(lineRepo.findByJournalEntryIdOrderByLineNumberAsc(70L)).thenReturn(List.of(
                JournalEntryLineEntity.builder().accountCode("1100").debitPaise(10000L).creditPaise(0L).build(),
                JournalEntryLineEntity.builder().accountCode("4010").debitPaise(0L).creditPaise(10000L).build()));

        service.reverse(70L, "Posted against the wrong customer");

        assertEquals("REVERSED", original.getStatus(), "the original is marked, not deleted");
        assertEquals(2, savedLines.size());
        assertEquals(0L, savedLines.get(0).getDebitPaise(), "the debit becomes a credit");
        assertEquals(10000L, savedLines.get(0).getCreditPaise());
    }

    @Test
    @DisplayName("A reversal without a reason is refused")
    void reversalNeedsAReason() {
        when(journalRepo.findById(70L)).thenReturn(Optional.of(JournalEntryEntity.builder()
                .id(70L).journalNumber("JV-70").status("POSTED").build()));

        VeloriaException ex = assertThrows(VeloriaException.class, () -> service.reverse(70L, "  "));
        assertTrue(ex.getMessage().contains("reason"));
    }

    @Test
    @DisplayName("An entry cannot be reversed twice")
    void cannotReverseTwice() {
        when(journalRepo.findById(70L)).thenReturn(Optional.of(JournalEntryEntity.builder()
                .id(70L).journalNumber("JV-70").status("REVERSED").build()));

        assertThrows(VeloriaException.class, () -> service.reverse(70L, "again"));
    }

    @Test
    @DisplayName("The financial year runs April to March")
    void financialYearRunsAprilToMarch() {
        assertEquals("2026-2027", JournalService.financialYear(LocalDate.of(2026, 4, 1)));
        assertEquals("2026-2027", JournalService.financialYear(LocalDate.of(2027, 3, 31)));
        assertEquals("2025-2026", JournalService.financialYear(LocalDate.of(2026, 3, 31)));
    }
}
