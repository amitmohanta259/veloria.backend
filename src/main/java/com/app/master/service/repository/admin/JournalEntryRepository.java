package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.JournalEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface JournalEntryRepository extends JpaRepository<JournalEntryEntity, Long> {

    List<JournalEntryEntity> findByPeriodAndStatusOrderByJournalDateAscIdAsc(String period, String status);

    List<JournalEntryEntity> findByStatusOrderByJournalDateAscIdAsc(String status);

    List<JournalEntryEntity> findByJournalDateBetweenAndStatusOrderByJournalDateAscIdAsc(
            LocalDate from, LocalDate to, String status);

    /** Posting is idempotent on the business record behind the entry. */
    Optional<JournalEntryEntity> findBySourceTypeAndSourceIdAndStatus(
            String sourceType, Long sourceId, String status);

    boolean existsBySourceTypeAndSourceIdAndStatus(String sourceType, Long sourceId, String status);

    /**
     * Whether this business record has <em>ever</em> been posted, reversed
     * entries included.
     *
     * <p>{@code ux_journal_source} only constrains {@code POSTED} rows, so a
     * reversed entry leaves the source looking unposted. Asking by status alone
     * is therefore not enough to decide whether a record has already been
     * accounted for: a backfill would see nothing POSTED and write the entry
     * again, undoing a reversal somebody made on purpose. That is exactly how
     * this database came to hold four SALE journals for every order.
     */
    boolean existsBySourceTypeAndSourceId(String sourceType, Long sourceId);

    /**
     * The receivable still standing for one order.
     *
     * <p>Everything that debited Accounts Receivable for this order — its sale and
     * its COD handling charge — less every credit against them: collections
     * already posted, and any reversal of those entries. Reading it from the
     * ledger rather than from the order means it cannot disagree with the books it
     * is supposed to describe.
     *
     * <p>A refund is deliberately absent. It returns cash and takes revenue back
     * out; it does not credit a receivable that the collection already cleared.
     */
    @Query(nativeQuery = true, value = """
            SELECT COALESCE(SUM(l.debit_paise) - SUM(l.credit_paise), 0)
              FROM journal_entry_line l
              JOIN journal_entry je ON je.id = l.journal_entry_id
             WHERE l.account_code = '1100'
               AND (   (je.source_type IN ('SALE', 'COD_FEE') AND je.source_id = :orderId)
                    OR (je.source_type = 'PAYMENT_COLLECTION' AND je.source_id IN
                        (SELECT pa.id FROM payment_attempt pa WHERE pa.customer_order_id = :orderId))
                    OR je.reverses_journal_id IN
                        (SELECT x.id FROM journal_entry x
                          WHERE x.source_type IN ('SALE', 'COD_FEE') AND x.source_id = :orderId))
            """)
    Long outstandingReceivableFor(@Param("orderId") Long orderId);
}
