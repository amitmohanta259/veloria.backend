package com.app.master.service.accounting;

import com.app.master.service.core.entity.AccountingPeriodEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.repository.admin.AccountingPeriodRepository;
import com.app.master.service.service.admin.PostingDateResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Where a journal is allowed to land when its own period is closed.
 *
 * <p>The approved policy (CP-1): the order stands, the transaction date is kept,
 * and the entry is posted in the next open period. These pin the four answers
 * that matter — open, closed, several closed in a row, and nowhere to go.
 */
class PostingDateResolverTest {

    private AccountingPeriodRepository periodRepo;
    private PostingDateResolver resolver;

    private static AccountingPeriodEntity period(String p, String status, LocalDate start) {
        return AccountingPeriodEntity.builder().period(p).status(status).periodStart(start).build();
    }

    @BeforeEach
    void setUp() {
        periodRepo = mock(AccountingPeriodRepository.class);
        when(periodRepo.findByStatusAndPeriodStartGreaterThanEqualOrderByPeriodStartAsc(any(), any()))
                .thenReturn(List.of());
        resolver = new PostingDateResolver(periodRepo);
    }

    @Test
    @DisplayName("an open period posts on the transaction's own date")
    void openPeriodPostsInPlace() throws Exception {
        LocalDate when = LocalDate.of(2026, 9, 28);
        when(periodRepo.findByPeriod("2026-09"))
                .thenReturn(Optional.of(period("2026-09", "OPEN", LocalDate.of(2026, 9, 1))));

        PostingDateResolver.Resolution r = resolver.resolve(when);

        assertEquals(when, r.postingDate());
        assertEquals(when, r.transactionDate());
        assertFalse(r.deferred(), "nothing was deferred");
        assertEquals("2026-09", r.postingPeriod());
    }

    /** The worked example from the approved decision. */
    @Test
    @DisplayName("28 September in a closed September posts on 1 October and keeps 28 September")
    void closedPeriodMovesToTheNextOpenOne() throws Exception {
        LocalDate when = LocalDate.of(2026, 9, 28);
        when(periodRepo.findByPeriod("2026-09"))
                .thenReturn(Optional.of(period("2026-09", "CLOSED", LocalDate.of(2026, 9, 1))));
        when(periodRepo.findByStatusAndPeriodStartGreaterThanEqualOrderByPeriodStartAsc(
                eq("OPEN"), eq(LocalDate.of(2026, 10, 1))))
                .thenReturn(List.of(period("2026-10", "OPEN", LocalDate.of(2026, 10, 1))));

        PostingDateResolver.Resolution r = resolver.resolve(when);

        assertEquals(LocalDate.of(2026, 10, 1), r.postingDate(), "posted in the next open period");
        assertEquals(LocalDate.of(2026, 9, 28), r.transactionDate(), "the transaction date does not move");
        assertTrue(r.deferred());
        assertEquals("2026-10", r.postingPeriod());
    }

    @Test
    @DisplayName("a LOCKED period is as closed as a CLOSED one")
    void lockedIsAlsoClosed() throws Exception {
        when(periodRepo.findByPeriod("2026-09"))
                .thenReturn(Optional.of(period("2026-09", "LOCKED", LocalDate.of(2026, 9, 1))));
        when(periodRepo.findByStatusAndPeriodStartGreaterThanEqualOrderByPeriodStartAsc(any(), any()))
                .thenReturn(List.of(period("2026-11", "OPEN", LocalDate.of(2026, 11, 1))));

        assertEquals(LocalDate.of(2026, 11, 1),
                resolver.resolve(LocalDate.of(2026, 9, 28)).postingDate());
    }

    @Test
    @DisplayName("several closed months in a row skip to the first open one, not the next month")
    void skipsEveryClosedPeriod() throws Exception {
        when(periodRepo.findByPeriod("2026-09"))
                .thenReturn(Optional.of(period("2026-09", "CLOSED", LocalDate.of(2026, 9, 1))));
        // The repository returns only OPEN periods, so October and November being
        // closed simply means they are not in this list.
        when(periodRepo.findByStatusAndPeriodStartGreaterThanEqualOrderByPeriodStartAsc(
                eq("OPEN"), eq(LocalDate.of(2026, 10, 1))))
                .thenReturn(List.of(
                        period("2026-12", "OPEN", LocalDate.of(2026, 12, 1)),
                        period("2027-01", "OPEN", LocalDate.of(2027, 1, 1))));

        PostingDateResolver.Resolution r = resolver.resolve(LocalDate.of(2026, 9, 28));

        assertEquals(LocalDate.of(2026, 12, 1), r.postingDate(), "the earliest open period, not the latest");
    }

    @Test
    @DisplayName("a period that was never set up counts as open, as the existing check has always held")
    void unknownPeriodIsTreatedAsOpen() throws Exception {
        when(periodRepo.findByPeriod("2019-04")).thenReturn(Optional.empty());

        LocalDate when = LocalDate.of(2019, 4, 10);
        assertEquals(when, resolver.resolve(when).postingDate());
    }

    @Test
    @DisplayName("with no open period after a closed one, nothing is posted and the reason says so")
    void noOpenPeriodIsRefusedRatherThanInvented() {
        when(periodRepo.findByPeriod("2027-03"))
                .thenReturn(Optional.of(period("2027-03", "CLOSED", LocalDate.of(2027, 3, 1))));

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> resolver.resolve(LocalDate.of(2027, 3, 20)));

        assertTrue(e.getMessage().contains("no open period follows"), e.getMessage());
        assertTrue(e.getMessage().contains("Open the next period first"),
                "the message must say what to do: " + e.getMessage());
        verify(periodRepo, never()).save(any());
    }

    @Test
    @DisplayName("an earlier open period is never chosen — a deferral only ever moves forward")
    void neverPostsBackwards() throws Exception {
        when(periodRepo.findByPeriod("2026-09"))
                .thenReturn(Optional.of(period("2026-09", "CLOSED", LocalDate.of(2026, 9, 1))));
        when(periodRepo.findByStatusAndPeriodStartGreaterThanEqualOrderByPeriodStartAsc(any(), any()))
                .thenReturn(List.of(period("2026-10", "OPEN", LocalDate.of(2026, 10, 1))));

        resolver.resolve(LocalDate.of(2026, 9, 28));

        // The search itself starts the day after the closed period ends, so an
        // open August can never be a candidate.
        verify(periodRepo).findByStatusAndPeriodStartGreaterThanEqualOrderByPeriodStartAsc(
                "OPEN", LocalDate.of(2026, 10, 1));
    }

    @Test
    @DisplayName("a transaction with no date is refused")
    void noDateIsRefused() {
        assertThrows(VeloriaException.class, () -> resolver.resolve(null));
    }
}
