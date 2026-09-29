package com.app.master.service.repository.testing;

import com.app.master.service.core.entity.TestResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TestResultRepository extends JpaRepository<TestResultEntity, Long> {

    List<TestResultEntity> findByRunIdOrderByStatusAscIdAsc(Long runId);

    List<TestResultEntity> findByRunIdAndStatusOrderByIdAsc(Long runId, String status);

    @Query("SELECT r.suite, r.status, COUNT(r) FROM TestResultEntity r WHERE r.runId = ?1 GROUP BY r.suite, r.status")
    List<Object[]> countBySuiteAndStatus(Long runId);

    long countByRunIdAndStatus(Long runId, String status);
}
