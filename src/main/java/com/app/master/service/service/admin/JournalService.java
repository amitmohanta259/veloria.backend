package com.app.master.service.service.admin;

import com.app.master.service.core.entity.*;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * The only way anything reaches the general ledger.
 *
 * Every business transaction — a sale, an expense, a payroll run — becomes a
 * balanced journal entry here, and every financial report is then derived from
 * those entries rather than from the source tables directly. That is what keeps
 * the reports agreeing with each other: they are reading the same rows.
 *
 * Three rules are enforced and none of them are advisory:
 *
 * 1. <b>Debits must equal credits.</b> An unbalanced entry is refused, not
 *    corrected. A ledger that accepts one is no longer a ledger.
 * 2. <b>Posting is idempotent on the source record.</b> A unique index on
 *    (source_type, source_id) means re-running a backfill cannot double the
 *    books, whatever the caller does.
 * 3. <b>Nothing is deleted.</b> A mistake is corrected by posting a reversal,
 *    which leaves both entries visible.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JournalService {

    public static final String POSTED   = "POSTED";
    public static final String REVERSED = "REVERSED";

    private final JournalEntryRepository journalRepo;
    private final JournalEntryLineRepository lineRepo;
    private final ChartOfAccountRepository accountRepo;
    private final AccountingPeriodRepository periodRepo;
    private final GstAuditService auditService;

    /** One side of an entry, as the caller describes it. */
    public record Posting(String accountCode, long debitPaise, long creditPaise, String description) {

        public static Posting debit(String account, long paise, String description) {
            return new Posting(account, paise, 0L, description);
        }

        public static Posting credit(String account, long paise, String description) {
            return new Posting(account, 0L, paise, description);
        }
    }

    /** A journal to post, before it has been validated or numbered. */
    public record Draft(LocalDate date, String reference, String sourceType, Long sourceId,
                        String description, List<Posting> postings) {}

    // ── Posting ──────────────────────────────────────────────────────────────

    /**
     * Posts a balanced entry to the ledger.
     *
     * Returns the existing entry when this source record has already been
     * posted, rather than creating a second one — callers may retry, and a
     * backfill may run more than once.
     */
    @Transactional(rollbackFor = Exception.class)
    public JournalEntryEntity post(Draft draft) throws VeloriaException {
        validate(draft);

        if (draft.sourceId() != null) {
            var existing = journalRepo.findBySourceTypeAndSourceIdAndStatus(
                    draft.sourceType(), draft.sourceId(), POSTED);
            if (existing.isPresent()) return existing.get();
        }

        String period = YearMonth.from(draft.date()).toString();
        assertPeriodOpen(period);

        long debits = draft.postings().stream().mapToLong(Posting::debitPaise).sum();
        long credits = draft.postings().stream().mapToLong(Posting::creditPaise).sum();

        JournalEntryEntity entry = journalRepo.save(JournalEntryEntity.builder()
                .journalNumber(nextJournalNumber(draft.date()))
                .journalDate(draft.date())
                .period(period)
                .financialYear(financialYear(draft.date()))
                .reference(draft.reference())
                .sourceType(draft.sourceType())
                .sourceId(draft.sourceId())
                .description(draft.description())
                .status(POSTED)
                .totalDebitPaise(debits)
                .totalCreditPaise(credits)
                .organizationId(1L)
                .createdBy(actor())
                .build());

        List<JournalEntryLineEntity> lines = new ArrayList<>();
        int n = 1;
        for (Posting p : draft.postings()) {
            lines.add(JournalEntryLineEntity.builder()
                    .journalEntryId(entry.getId())
                    .lineNumber(n++)
                    .accountCode(p.accountCode())
                    .debitPaise(p.debitPaise())
                    .creditPaise(p.creditPaise())
                    .description(p.description())
                    .build());
        }
        lineRepo.saveAll(lines);
        entry.setLines(lines);

        auditService.log("JOURNAL_ENTRY", entry.getId(), entry.getJournalNumber(),
                "JOURNAL_POSTED", period, actor());

        log.debug("Posted {} — {} line(s), {} paise each side",
                entry.getJournalNumber(), lines.size(), debits);
        return entry;
    }

    /**
     * Reverses an entry by posting its mirror image.
     *
     * The original stays exactly as it was: a correction that erased the mistake
     * would leave nothing to audit.
     */
    @Transactional(rollbackFor = Exception.class)
    public JournalEntryEntity reverse(Long journalId, String reason) throws VeloriaException {
        JournalEntryEntity original = journalRepo.findById(journalId)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Journal entry " + journalId + " was not found"));

        if (REVERSED.equals(original.getStatus())) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "Journal " + original.getJournalNumber() + " has already been reversed");
        }
        if (reason == null || reason.isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A reversal needs a reason — it becomes part of the audit trail");
        }

        List<Posting> mirrored = lineRepo.findByJournalEntryIdOrderByLineNumberAsc(journalId).stream()
                .map(l -> new Posting(l.getAccountCode(),
                        nz(l.getCreditPaise()), nz(l.getDebitPaise()),
                        "Reversal — " + nvl(l.getDescription())))
                .toList();

        JournalEntryEntity reversal = post(new Draft(
                LocalDate.now(), original.getReference(), "REVERSAL", null,
                "Reversal of " + original.getJournalNumber() + " — " + reason, mirrored));
        reversal.setReversesJournalId(original.getId());
        journalRepo.save(reversal);

        original.setStatus(REVERSED);
        journalRepo.save(original);

        auditService.log("JOURNAL_ENTRY", original.getId(), original.getJournalNumber(),
                "JOURNAL_REVERSED", original.getPeriod(), actor());
        return reversal;
    }

    // ── Validation ───────────────────────────────────────────────────────────

    private void validate(Draft draft) throws VeloriaException {
        if (draft == null || draft.postings() == null || draft.postings().isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A journal entry needs at least one posting");
        }
        if (draft.date() == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A journal entry needs a date — it determines the period it lands in");
        }

        long debits = 0, credits = 0;
        for (Posting p : draft.postings()) {
            if (p.debitPaise() < 0 || p.creditPaise() < 0) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "A posting cannot be negative. Use the other side of the entry instead.");
            }
            if ((p.debitPaise() == 0) == (p.creditPaise() == 0)) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Posting to " + p.accountCode() + " must be either a debit or a credit, "
                        + "not both and not neither");
            }
            if (accountRepo.findByCode(p.accountCode()).isEmpty()) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Account " + p.accountCode() + " is not in the chart of accounts");
            }
            debits += p.debitPaise();
            credits += p.creditPaise();
        }

        if (debits != credits) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Entry does not balance: debits " + debits + " paise, credits " + credits
                    + " paise, difference " + Math.abs(debits - credits) + ". "
                    + "An unbalanced entry is refused rather than adjusted.");
        }
        if (debits == 0) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A journal entry of zero has nothing to record");
        }
    }

    private void assertPeriodOpen(String period) throws VeloriaException {
        var p = periodRepo.findByPeriod(period);
        if (p.isPresent() && !"OPEN".equals(p.get().getStatus())) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "Accounting period " + period + " is " + p.get().getStatus()
                    + ". Post a reversal or an adjustment in an open period instead.");
        }
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    public List<JournalEntryEntity> forPeriod(String period) {
        return journalRepo.findByPeriodAndStatusOrderByJournalDateAscIdAsc(period, POSTED);
    }

    public List<JournalEntryLineEntity> linesOf(Long journalId) {
        return lineRepo.findByJournalEntryIdOrderByLineNumberAsc(journalId);
    }

    public boolean alreadyPosted(String sourceType, Long sourceId) {
        return sourceId != null
                && journalRepo.existsBySourceTypeAndSourceIdAndStatus(sourceType, sourceId, POSTED);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String nextJournalNumber(LocalDate date) {
        return "JV-" + YearMonth.from(date) + "-" + System.nanoTime() % 1_000_000;
    }

    public static String financialYear(LocalDate d) {
        int y = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return y + "-" + (y + 1);
    }

    private String actor() {
        try {
            return org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "system";
        }
    }

    private static long nz(Long v) { return v == null ? 0L : v; }

    private static String nvl(String v) { return v == null ? "" : v; }
}
