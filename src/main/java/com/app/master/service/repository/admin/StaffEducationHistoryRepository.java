package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.StaffEducationHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StaffEducationHistoryRepository extends JpaRepository<StaffEducationHistoryEntity, Long> {

    List<StaffEducationHistoryEntity> findByStaffId(Long staffId);

    void deleteByStaffId(Long staffId);
}