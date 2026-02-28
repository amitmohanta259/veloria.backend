package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.InventorySubCategoryEntity;
import com.app.master.service.core.response.admin.InventoryCollectionAllResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InventorySubCategoryRepository extends JpaRepository<InventorySubCategoryEntity, Long> {

    Optional<InventorySubCategoryEntity> findByUuid(UUID uuid);

    @Query(value = """
            SELECT new com.app.master.service.core.response.admin.InventoryCollectionAllResponse(
            isc.uuid,
            isc.name,
            isc.description,
            isc.active
            )
            FROM InventorySubCategoryEntity isc
            LEFT JOIN InventoryCollectionEntity i ON i.id = isc.collectionId
            WHERE isc.archive = false
            AND (:collectionUuid IS NULL OR i.uuid = :collectionUuid)
            AND (:search IS NULL OR LOWER(isc.name) LIKE :search OR LOWER(isc.description) LIKE :search)
            """)
    Page<InventoryCollectionAllResponse> allInventorySubCategory(@Param("collectionUuid") UUID collectionUuid, @Param("search") String search, Pageable pageable);

}