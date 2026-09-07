package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstStateMasterEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GstStateMasterRepository extends JpaRepository<GstStateMasterEntity, Long> {
    List<GstStateMasterEntity> findAllByActiveTrueOrderByStateName();
    Optional<GstStateMasterEntity> findByStateCodeAndActiveTrue(String stateCode);
}
