package com.app.master.service.repository.testing;

import com.app.master.service.core.entity.TestRunEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TestRunRepository extends JpaRepository<TestRunEntity, Long> {

    Optional<TestRunEntity> findByRunNumber(String runNumber);

    Page<TestRunEntity> findAllByOrderByRequestedAtDesc(Pageable pageable);

    Optional<TestRunEntity> findFirstByOrderByRequestedAtDesc();

    @Query("SELECT r FROM TestRunEntity r WHERE r.status IN ('QUEUED', 'RUNNING')")
    List<TestRunEntity> findActive();

    @Query(value = """
           SELECT COALESCE(MAX(CAST(SUBSTRING(run_number FROM 14) AS INT)), 0)
             FROM engineering_test_run WHERE run_number LIKE :prefix || '%'
           """, nativeQuery = true)
    int maxSequenceForPrefix(@Param("prefix") String prefix);
}
