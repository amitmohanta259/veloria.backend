package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstCreditNoteItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GstCreditNoteItemRepository extends JpaRepository<GstCreditNoteItemEntity, Long> {

    List<GstCreditNoteItemEntity> findByCreditNoteIdOrderByIdAsc(Long creditNoteId);
}
