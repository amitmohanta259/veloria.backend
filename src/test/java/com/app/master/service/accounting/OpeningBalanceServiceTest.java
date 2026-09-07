package com.app.master.service.accounting;

import com.app.master.service.core.entity.ChartOfAccountEntity;
import com.app.master.service.core.entity.JournalEntryEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.repository.admin.ChartOfAccountRepository;
import com.app.master.service.repository.admin.JournalEntryRepository;
import com.app.master.service.service.admin.JournalService;
import com.app.master.service.service.admin.JournalService.Draft;
import com.app.master.service.service.admin.JournalService.Posting;
import com.app.master.service.service.admin.OpeningBalanceService;
import com.app.master.service.service.admin.OpeningBalanceService.OpeningLine;
import com.app.master.service.service.admin.OpeningBalanceService.Submission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Opening balances.
 *
 * The rules under test: nothing is assumed, nothing is plugged, and the entry
 * goes through the same journal validation as everything else.
 */
class OpeningBalanceServiceTest {

    private JournalService journal;
    private JournalEntryRepository journalRepo;
    private ChartOfAccountRepository accountRepo;
    private OpeningBalanceService service;

    /** code -> {type, normalBalance} */
    private static final Map<String, String[]> CHART = Map.of(
            "1010", new String[]{"ASSET", "DEBIT"},
            "1200", new String[]{"ASSET", "DEBIT"},
            "2010", new String[]{"LIABILITY", "CREDIT"},
            "3010", new String[]{"EQUITY", "CREDIT"},
            "4010", new String[]{"REVENUE", "CREDIT"});

    @BeforeEach
    void setUp() throws Exception {
        journal = mock(JournalService.class);
        journalRepo = mock(JournalEntryRepository.class);
        accountRepo = mock(ChartOfAccountRepository.class);

        when(accountRepo.findByCode(anyString())).thenAnswer(i -> {
            String code = i.getArgument(0);
            String[] meta = CHART.get(code);
            if (meta == null) return Optional.empty();
            return Optional.of(ChartOfAccountEntity.builder()
                    .code(code).name("Account " + code)
                    .accountType(meta[0]).normalBalance(meta[1]).build());
        });
        when(accountRepo.findByActiveTrueOrderByCodeAsc()).thenReturn(List.of(
                ChartOfAccountEntity.builder().code("1010").name("Cash")
                        .accountType("ASSET").normalBalance("DEBIT").build()));
        when(journalRepo.findByStatusOrderByJournalDateAscIdAsc(anyString())).thenReturn(List.of());
        when(journal.post(any())).thenAnswer(i -> JournalEntryEntity.builder().id(1L).build());

        service = new OpeningBalanceService(journal, journalRepo, accountRepo);
    }

    private Draft captured() throws Exception {
        ArgumentCaptor<Draft> c = ArgumentCaptor.forClass(Draft.class);
        verify(journal).post(c.capture());
        return c.getValue();
    }

    @Test
    @DisplayName("Balances post on each account's normal side")
    void postsOnNormalSide() throws Exception {
        service.post(new Submission(LocalDate.of(2026, 7, 31), List.of(
                new OpeningLine("1200", 6345086L),   // asset  -> debit
                new OpeningLine("3010", 6345086L)),  // equity -> credit
                "as confirmed by the owner"));

        Draft d = captured();
        Posting inventory = d.postings().stream()
                .filter(p -> p.accountCode().equals("1200")).findFirst().orElseThrow();
        Posting capital = d.postings().stream()
                .filter(p -> p.accountCode().equals("3010")).findFirst().orElseThrow();

        assertEquals(6345086L, inventory.debitPaise(), "an asset opens as a debit");
        assertEquals(6345086L, capital.creditPaise(), "equity opens as a credit");
        assertEquals("OPENING_BALANCE", d.sourceType());
    }

    @Test
    @DisplayName("An unbalanced submission is refused and nothing is plugged")
    void unbalancedIsRefusedWithoutPlugging() throws Exception {
        VeloriaException e = assertThrows(VeloriaException.class, () ->
                service.post(new Submission(LocalDate.of(2026, 7, 31), List.of(
                        new OpeningLine("1200", 6345086L),
                        new OpeningLine("3010", 5000000L)), null)));

        assertTrue(e.getMessage().contains("do not balance"));
        assertTrue(e.getMessage().contains("1345086"), "the difference must be stated");
        assertTrue(e.getMessage().contains("No balancing entry has been created"));
        verify(journal, never()).post(any());
    }

    @Test
    @DisplayName("A negative balance opens on the opposite side — an overdrawn bank")
    void negativeOpensOnTheOppositeSide() throws Exception {
        service.post(new Submission(LocalDate.of(2026, 7, 31), List.of(
                new OpeningLine("1010", -50000L),   // asset, negative -> credit
                new OpeningLine("2010", -50000L)),  // liability, negative -> debit
                null));

        Draft d = captured();
        assertEquals(50000L, d.postings().stream()
                .filter(p -> p.accountCode().equals("1010")).findFirst().orElseThrow().creditPaise());
        assertEquals(50000L, d.postings().stream()
                .filter(p -> p.accountCode().equals("2010")).findFirst().orElseThrow().debitPaise());
    }

    @Test
    @DisplayName("An omitted account contributes nothing — it is not assumed to be zero")
    void zeroLinesAreSkippedNotAsserted() throws Exception {
        service.post(new Submission(LocalDate.of(2026, 7, 31), List.of(
                new OpeningLine("1200", 100000L),
                new OpeningLine("1010", 0L),
                new OpeningLine("3010", 100000L)), null));

        Draft d = captured();
        assertTrue(d.postings().stream().noneMatch(p -> p.accountCode().equals("1010")),
                "a zero line produces no posting at all");
        assertEquals(2, d.postings().size());
    }

    @Test
    @DisplayName("Revenue and expense accounts are refused — prior trading is retained earnings")
    void revenueAccountsAreRefused() {
        VeloriaException e = assertThrows(VeloriaException.class, () ->
                service.post(new Submission(LocalDate.of(2026, 7, 31), List.of(
                        new OpeningLine("4010", 100000L)), null)));
        assertTrue(e.getMessage().contains("retained earnings"));
    }

    @Test
    @DisplayName("An account outside the chart is refused")
    void unknownAccountIsRefused() {
        assertThrows(VeloriaException.class, () ->
                service.post(new Submission(LocalDate.of(2026, 7, 31), List.of(
                        new OpeningLine("9999", 100L)), null)));
    }

    @Test
    @DisplayName("A submission with no cut-off date is refused")
    void missingDateIsRefused() {
        assertThrows(VeloriaException.class, () ->
                service.post(new Submission(null, List.of(new OpeningLine("1200", 1L)), null)));
    }

    @Test
    @DisplayName("Opening balances cannot be posted twice")
    void cannotPostTwice() {
        when(journalRepo.findByStatusOrderByJournalDateAscIdAsc(anyString())).thenReturn(List.of(
                JournalEntryEntity.builder().id(1L).sourceType("OPENING_BALANCE")
                        .journalDate(LocalDate.of(2026, 7, 31)).build()));

        VeloriaException e = assertThrows(VeloriaException.class, () ->
                service.post(new Submission(LocalDate.of(2026, 7, 31), List.of(
                        new OpeningLine("1200", 100L), new OpeningLine("3010", 100L)), null)));
        assertTrue(e.getMessage().contains("already been posted"));
    }

    @Test
    @DisplayName("Readiness reports nothing posted, and says no value will be assumed")
    void readinessReportsOutstanding() {
        var r = service.readiness();
        assertFalse(r.posted());
        assertFalse(r.accountsNotProvided().isEmpty());
        assertTrue(r.blockers().stream().anyMatch(b -> b.contains("No value will be assumed")));
    }
}
