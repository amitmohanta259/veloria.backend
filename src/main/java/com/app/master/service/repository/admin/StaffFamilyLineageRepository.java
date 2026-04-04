package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.StaffFamilyLineageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StaffFamilyLineageRepository extends JpaRepository<StaffFamilyLineageEntity, Long> {

    List<StaffFamilyLineageEntity> findByStaffId(Long staffId);

    void deleteByStaffId(Long staffId);
}
