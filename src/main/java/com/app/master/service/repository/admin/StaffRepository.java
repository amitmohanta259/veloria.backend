package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.StaffEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StaffRepository extends JpaRepository<StaffEntity, Long> {

    Optional<StaffEntity> findByUuid(UUID uuid);
}
