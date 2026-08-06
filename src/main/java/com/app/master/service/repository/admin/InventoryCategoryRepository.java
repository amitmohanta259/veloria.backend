package com.app.master.service.repository.admin;

import com.app.master.service.core.dto.InventoryCategory;
import com.app.master.service.core.entity.InventoryCategoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InventoryCategoryRepository extends JpaRepository<InventoryCategoryEntity, Long> {

    Optional<InventoryCategoryEntity> findByUuid(UUID uuid);

    @Query(value = """
            SELECT new com.app.master.service.core.dto.InventoryCategory(
            i.uuid,
            i.name,
            i.description
            )
            FROM InventoryCategoryEntity i
            WHERE (:search IS NULL
                           OR LOWER(i.name) LIKE LOWER(CONCAT('%', :search, '%'))
                           OR LOWER(i.description) LIKE LOWER(CONCAT('%', :search, '%')))
            AND i.active = true
            AND i.archive = false
            """)
    Page<InventoryCategory> allInventoryCategory(@Param("search") String search, Pageable pageable);

    @Query(value = """
            SELECT new com.app.master.service.core.dto.InventoryCategory(
            i.uuid,
            i.name,
            i.description
            )
            FROM InventoryCategoryEntity i
            WHERE i.active = true
            AND i.archive = false
            ORDER BY i.name ASC
            """)
    List<InventoryCategory> listAllInventoryCategory();

}