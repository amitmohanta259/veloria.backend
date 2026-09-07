package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.JournalEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
