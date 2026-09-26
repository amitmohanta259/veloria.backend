package com.app.master.service.service.admin;

import com.app.master.service.core.entity.AccountingPeriodEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.AccountingPeriodRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Where a transaction's journal is allowed to land.
 *
 * <p>A business event has one date: the day it happened. A journal has two, and
 * conflating them is what left a closed-period sale with nowhere to go. This
 * separates them:
 *
 * <ul>
 *   <li><b>transaction date</b> — when the order was placed, the cash was taken,
 *       the refund was issued. It never moves.</li>
 *   <li><b>posting date</b> — the date whose accounting period the entry is
 *       recorded in. Normally the same day; when that period is closed, the
 *       first day of the next open period.</li>
 * </ul>
 *
 * <p>Nothing here reopens, unlocks or writes to a period. A closed period stays
 * closed; the entry moves, not the period.
 *
 * <p>When no open period exists at or after the transaction date this refuses to
 * resolve a date rather than choosing one. Posting into the past would breach
 * the lock and posting into a period that has not been set up would create an
 * entry no report knows about — so the honest answer is that the books cannot
 * accept this entry yet, and someone has to open a period.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PostingDateResolver {

    private static final String OPEN = "OPEN";

    private final AccountingPeriodRepository periodRepo;

    /** A transaction date, and the date its journal may actually be posted on. */
    public record Resolution(LocalDate transactionDate, LocalDate postingDate, boolean deferred) {

        public String postingPeriod() {
            return YearMonth.from(postingDate).toString();
        }
    }

    /**
     * Resolves the posting date for a transaction.
     *
     * @throws VeloriaException when the transaction's period is closed and no
     *                          open period follows it
     */
    public Resolution resolve(LocalDate transactionDate) throws VeloriaException {
        if (transactionDate == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A transaction needs a date before it can be posted");
        }

        if (isOpen(YearMonth.from(transactionDate).toString())) {
            return new Resolution(transactionDate, transactionDate, false);
        }

        // The transaction's own period is closed. The next open period starts
        // after it, so look from the day after the closed period ends rather
        // than from the transaction date — a later date inside the same closed
        // month is not a candidate.
        LocalDate searchFrom = YearMonth.from(transactionDate).atEndOfMonth().plusDays(1);
        List<AccountingPeriodEntity> open = periodRepo
                .findByStatusAndPeriodStartGreaterThanEqualOrderByPeriodStartAsc(OPEN, searchFrom);

        if (open.isEmpty()) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "Accounting period " + YearMonth.from(transactionDate)
                    + " is closed and no open period follows it, so this transaction dated "
                    + transactionDate + " cannot be posted. Open the next period first — "
                    + "nothing will be posted into a closed one.");
        }

        AccountingPeriodEntity target = open.get(0);
        log.info("Period {} is closed; posting the transaction dated {} into {} on {} instead",
                YearMonth.from(transactionDate), transactionDate,
                target.getPeriod(), target.getPeriodStart());
        return new Resolution(transactionDate, target.getPeriodStart(), true);
    }

    private boolean isOpen(String period) {
        Optional<AccountingPeriodEntity> p = periodRepo.findByPeriod(period);
        // A period with no row has never been closed. The existing period check
        // in JournalService takes the same view, and changing it here would
        // silently reclassify every entry that predates the period table.
        return p.isEmpty() || OPEN.equals(p.get().getStatus());
    }
}
