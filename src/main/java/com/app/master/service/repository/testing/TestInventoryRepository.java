package com.app.master.service.repository.testing;

import com.app.master.service.core.entity.TestInventoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TestInventoryRepository extends JpaRepository<TestInventoryEntity, Long> {

    Optional<TestInventoryEntity> findByItemId(String itemId);

    @Query("""
           SELECT i FROM TestInventoryEntity i
            WHERE (:kind   IS NULL OR i.itemKind   = :kind)
              AND (:module IS NULL OR i.module     = :module)
              AND (:status IS NULL OR i.testStatus = :status)
            ORDER BY i.itemId
           """)
    Page<TestInventoryEntity> search(@Param("kind") String kind,
                                     @Param("module") String module,
                                     @Param("status") String status,
                                     Pageable pageable);

    /** The coverage denominator and numerator in one query, per kind. */
    @Query("SELECT i.itemKind, i.testStatus, COUNT(i) FROM TestInventoryEntity i GROUP BY i.itemKind, i.testStatus")
    List<Object[]> countByKindAndStatus();

    @Query("SELECT i.module, i.testStatus, COUNT(i) FROM TestInventoryEntity i GROUP BY i.module, i.testStatus")
    List<Object[]> countByModuleAndStatus();

    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(item_id FROM LENGTH(:prefix) + 1) AS INT)), 0) "
                 + "FROM engineering_test_inventory WHERE item_id LIKE :prefix || '%'", nativeQuery = true)
    int maxSequenceForPrefix(@Param("prefix") String prefix);
}
