package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.StaffInsuranceCoverageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StaffInsuranceCoverageRepository extends JpaRepository<StaffInsuranceCoverageEntity, Long> {

    List<StaffInsuranceCoverageEntity> findByStaffId(Long staffId);

    void deleteByStaffId(Long staffId);
}
