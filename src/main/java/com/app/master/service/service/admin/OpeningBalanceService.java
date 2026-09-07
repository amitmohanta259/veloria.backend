package com.app.master.service.service.admin;

import com.app.master.service.core.entity.ChartOfAccountEntity;
import com.app.master.service.core.entity.JournalEntryEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.ChartOfAccountRepository;
import com.app.master.service.repository.admin.JournalEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Opening balances — the position the books start from.
 *
 * The ledger only knows what has been posted into it. Until the balances the
 * business already held at a cut-off date are entered, the balance sheet is
 * arithmetically correct but describes only recent activity. This is the
 * controlled way to enter them.
 *
 * Three refusals, all deliberate:
 *
 * 1. <b>No value is ever assumed.</b> An account omitted from the submission is
 *    omitted from the journal. Nothing is defaulted to zero, because "we hold no
 *    cash" and "nobody told me the cash balance" are different statements and
 *    the books must not confuse them.
 * 2. <b>No balancing figure is manufactured.</b> If debits and credits differ
 *    the submission is refused with the difference named. Plugging the gap into
 *    a suspense account would produce a balance sheet that balances and is
 *    wrong.
 * 3. <b>It posts through {@link JournalService} like everything else.</b> The
 *    same double-entry validation, the same idempotency, the same audit trail.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpeningBalanceService {

    public static final String SOURCE_TYPE = "OPENING_BALANCE";

    private final JournalService journal;
    private final JournalEntryRepository journalRepo;
    private final ChartOfAccountRepository accountRepo;

    /** One account's opening balance, as the business states it. */
    public record OpeningLine(String accountCode, long amountPaise) {}

    /** A submission: the cut-off date and the balances held on it. */
    public record Submission(LocalDate asOfDate, List<OpeningLine> lines, String note) {}

    /** What is still needed before opening balances can be posted. */
    public record Readiness(boolean posted, String asOfDate,
                            List<String> accountsProvided,
                            List<String> accountsNotProvided,
                            long totalDebitPaise, long totalCreditPaise,
                            boolean balanced, long differencePaise,
                            List<String> blockers) {}

    // ── Posting ──────────────────────────────────────────────────────────────

    /**
     * Posts the opening balances as a single journal dated the day before the
     * books begin.
     *
     * Each amount is placed on the account's normal side — an asset debits, a
     * liability or equity credits — so the caller states "the business holds
     * this much" rather than having to reason about sides.
     */
    @Transactional(rollbackFor = Exception.class)
    public JournalEntryEntity post(Submission submission) throws VeloriaException {
        validate(submission);

        if (existing().isPresent()) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "Opening balances have already been posted. Reverse that journal before "
                    + "entering different figures, so the change stays visible.");
        }

        List<JournalService.Posting> postings = new ArrayList<>();
        for (OpeningLine line : submission.lines()) {
            if (line.amountPaise() == 0) continue;   // omitted, not asserted as zero
            ChartOfAccountEntity account = account(line.accountCode());
            boolean debitSide = "DEBIT".equals(account.getNormalBalance());

            // A negative figure means the account sits on the opposite side —
            // an overdrawn bank, or accumulated losses in equity.
            long amount = Math.abs(line.amountPaise());
            boolean debit = line.amountPaise() > 0 ? debitSide : !debitSide;

            postings.add(debit
                    ? JournalService.Posting.debit(account.getCode(), amount,
                            "Opening " + account.getName())
                    : JournalService.Posting.credit(account.getCode(), amount,
                            "Opening " + account.getName()));
        }

        if (postings.isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "No opening balances were supplied. Nothing has been posted.");
        }

        long debits = postings.stream().mapToLong(JournalService.Posting::debitPaise).sum();
        long credits = postings.stream().mapToLong(JournalService.Posting::creditPaise).sum();
        if (debits != credits) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Opening balances do not balance: debits " + debits + " paise against credits "
                    + credits + " paise, a difference of " + Math.abs(debits - credits) + ". "
                    + "Assets must equal liabilities plus equity. No balancing entry has been "
                    + "created — that would produce a balance sheet that balances and is wrong. "
                    + "Check the figures, most often owner capital or retained earnings.");
        }

        JournalEntryEntity entry = journal.post(new JournalService.Draft(
                submission.asOfDate(), "OPENING-" + submission.asOfDate(),
                SOURCE_TYPE, null,
                "Opening balances as at " + submission.asOfDate()
                        + (submission.note() == null ? "" : " — " + submission.note()),
                postings));

        log.info("Opening balances posted as at {}: {} account(s), {} paise each side",
                submission.asOfDate(), postings.size(), debits);
        return entry;
    }

    // ── Readiness ────────────────────────────────────────────────────────────

    /**
     * What has been provided and what is still outstanding.
     *
     * Drives the "opening balances pending" state the reports show, so nobody
     * mistakes a partially-built ledger for a complete one.
     */
    public Readiness readiness() {
        Optional<JournalEntryEntity> posted = existing();

        if (posted.isPresent()) {
            JournalEntryEntity e = posted.get();
            return new Readiness(true, e.getJournalDate().toString(),
                    journal.linesOf(e.getId()).stream().map(l -> l.getAccountCode()).toList(),
                    List.of(),
                    e.getTotalDebitPaise(), e.getTotalCreditPaise(),
                    Objects.equals(e.getTotalDebitPaise(), e.getTotalCreditPaise()), 0L,
                    List.of());
        }

        // Nothing posted: every balance-sheet account is outstanding.
        List<String> outstanding = accountRepo.findByActiveTrueOrderByCodeAsc().stream()
                .filter(a -> !"REVENUE".equals(a.getAccountType())
                        && !"EXPENSE".equals(a.getAccountType()))
                .map(a -> a.getCode() + " " + a.getName())
                .toList();

        return new Readiness(false, null, List.of(), outstanding, 0L, 0L, false, 0L, List.of(
                "Opening balances have not been posted. Until they are, the ledger reflects only "
              + "transactions recorded since it began, so the balance sheet understates the "
              + "position.",
                "A cut-off date is required, along with the balances held on that date. No value "
              + "will be assumed — an account left out is left out, not treated as zero."));
    }

    /** The opening balance journal, if one has been posted. */
    public Optional<JournalEntryEntity> existing() {
        return journalRepo.findByStatusOrderByJournalDateAscIdAsc(JournalService.POSTED).stream()
                .filter(e -> SOURCE_TYPE.equals(e.getSourceType()))
                .findFirst();
    }

    // ── Validation ───────────────────────────────────────────────────────────

    private void validate(Submission submission) throws VeloriaException {
        if (submission == null || submission.asOfDate() == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A cut-off date is required — it fixes the point the books start from");
        }
        if (submission.lines() == null || submission.lines().isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "No opening balances were supplied");
        }
        for (OpeningLine line : submission.lines()) {
            ChartOfAccountEntity a = accountRepo.findByCode(line.accountCode()).orElse(null);
            if (a == null) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Account " + line.accountCode() + " is not in the chart of accounts");
            }
            if ("REVENUE".equals(a.getAccountType()) || "EXPENSE".equals(a.getAccountType())) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Account " + line.accountCode() + " (" + a.getName() + ") is a "
                        + a.getAccountType().toLowerCase() + " account. Opening balances belong to "
                        + "assets, liabilities and equity; prior trading is carried in retained "
                        + "earnings, not re-entered as revenue or expense.");
            }
        }
    }

    private ChartOfAccountEntity account(String code) throws VeloriaException {
        return accountRepo.findByCode(code)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Account " + code + " is not in the chart of accounts"));
    }
}
