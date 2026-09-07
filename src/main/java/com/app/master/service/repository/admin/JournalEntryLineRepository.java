package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.JournalEntryLineEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JournalEntryLineRepository extends JpaRepository<JournalEntryLineEntity, Long> {
    List<JournalEntryLineEntity> findByJournalEntryIdOrderByLineNumberAsc(Long journalEntryId);
    List<JournalEntryLineEntity> findByAccountCodeOrderByIdAsc(String accountCode);
}
