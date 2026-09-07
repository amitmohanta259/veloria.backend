package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.GstHsnMasterEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GstHsnMasterRepository extends JpaRepository<GstHsnMasterEntity, Long> {
    List<GstHsnMasterEntity> findByHsnCodeStartingWithAndActiveTrueOrderByHsnCode(String prefix);
    List<GstHsnMasterEntity> findByDescriptionContainingIgnoreCaseAndActiveTrueOrderByHsnCode(String query);
    List<GstHsnMasterEntity> findByChapterAndActiveTrueOrderByHsnCode(String chapter);
}
