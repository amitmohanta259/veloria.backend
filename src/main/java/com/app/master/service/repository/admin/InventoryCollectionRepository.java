package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.InventoryCollectionEntity;
import com.app.master.service.core.response.admin.InventoryCollectionAllResponse;
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
public interface InventoryCollectionRepository extends JpaRepository<InventoryCollectionEntity, Long> {

    Optional<InventoryCollectionEntity> findByUuid(UUID uuid);

    @Query(value = """
            SELECT new com.app.master.service.core.response.admin.InventoryCollectionAllResponse(
            ic.uuid,
            ic.name,
            ic.description,
            ic.active
            )
            FROM InventoryCollectionEntity ic
            LEFT JOIN InventoryCategoryEntity i ON i.id = ic.categoryId
            WHERE ic.archive = false
            AND (:categoryUuid IS NULL OR i.uuid = :categoryUuid)
            AND (:search IS NULL OR LOWER(ic.name) LIKE :search OR LOWER(ic.description) LIKE :search)
            """)
    Page<InventoryCollectionAllResponse> allInventoryCollection(@Param("categoryUuid") UUID categoryUuid, @Param("search") String search, Pageable pageable);

    @Query(value = """
            SELECT new com.app.master.service.core.response.admin.InventoryCollectionAllResponse(
            ic.uuid, ic.name, ic.description, ic.active, i.name
            )
            FROM InventoryCollectionEntity ic
            LEFT JOIN InventoryCategoryEntity i ON i.id = ic.categoryId
            WHERE ic.archive = false
            ORDER BY ic.name ASC
            """)
    List<InventoryCollectionAllResponse> listAllInventoryCollection();

    @Query(value = """
            SELECT new com.app.master.service.core.response.admin.InventoryCollectionAllResponse(
            ic.uuid, ic.name, ic.description, ic.active, i.name
            )
            FROM InventoryCollectionEntity ic
            JOIN InventoryCategoryEntity i ON i.id = ic.categoryId
            WHERE ic.archive = false
            AND i.uuid = :categoryUuid
            ORDER BY ic.name ASC
            """)
    List<InventoryCollectionAllResponse> listAllInventoryCollectionByCategory(@Param("categoryUuid") UUID categoryUuid);

}