package com.app.master.service.repository.engineering;

import com.app.master.service.core.entity.EngineeringAnomalyEvidenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EngineeringAnomalyEvidenceRepository extends JpaRepository<EngineeringAnomalyEvidenceEntity, Long> {
    List<EngineeringAnomalyEvidenceEntity> findByAnomalyIdOrderByIdAsc(Long anomalyId);
}
