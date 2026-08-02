package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.StaffResidencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StaffResidencyRepository extends JpaRepository<StaffResidencyEntity, Long> {

    List<StaffResidencyEntity> findByStaffId(Long staffId);

    void deleteByStaffId(Long staffId);
}
