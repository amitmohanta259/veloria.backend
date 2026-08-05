package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.StaffEntity;
import com.app.master.service.core.enums.StaffDepartment;
import com.app.master.service.core.response.admin.StaffListResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StaffRepository extends JpaRepository<StaffEntity, Long> {

    Optional<StaffEntity> findByUuid(UUID uuid);

    @Query("""
            SELECT new com.app.master.service.core.response.admin.StaffListResponse(
                s.uuid, s.avatar, s.department, s.designation, s.workEmail, s.phone,
                s.joiningDate, s.resignDate, s.active
            )
            FROM StaffEntity s
            WHERE s.archive = false
            AND (:search IS NULL
                 OR LOWER(s.designation) LIKE CONCAT('%', LOWER(:search), '%')
                 OR LOWER(s.workEmail) LIKE CONCAT('%', LOWER(:search), '%'))
            AND (:department IS NULL OR s.department = :department)
            AND (:active IS NULL OR s.active = :active)
            """)
    Page<StaffListResponse> getStaffList(@Param("search") String search,
                                         @Param("department") StaffDepartment department,
                                         @Param("active") Boolean active,
                                         Pageable pageable);
}
