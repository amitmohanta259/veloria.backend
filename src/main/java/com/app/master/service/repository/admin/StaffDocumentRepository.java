package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.StaffDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StaffDocumentRepository extends JpaRepository<StaffDocumentEntity, Long> {

    List<StaffDocumentEntity> findByStaffIdOrderByUploadedAtDesc(Long staffId);
}
