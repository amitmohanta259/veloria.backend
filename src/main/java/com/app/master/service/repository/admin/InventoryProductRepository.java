package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.InventoryProductEntity;
import com.app.master.service.core.response.admin.InventoryProductListResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InventoryProductRepository extends JpaRepository<InventoryProductEntity, Long> {

    Optional<InventoryProductEntity> findByUuid(UUID productUuid);

    @Query(value = """
            SELECT new com.app.master.service.core.response.admin.InventoryProductListResponse(
            ip.uuid,
            ip.name,
            ip.description,
            ip.skuId,
            ip.price,
            ip.priceCurrency,
            ip.initialStock,
            ip.visibility,
            ip.active
            )
            FROM InventoryProductEntity ip
            LEFT JOIN InventorySubCategoryEntity isc ON isc.id = ip.subCategoryId
            WHERE (:subCategoryUuid IS NULL OR isc.uuid = :subCategoryUuid)
            AND (:search IS NULL
                 OR LOWER(ip.name) LIKE CONCAT('%', LOWER(:search), '%')
                 OR LOWER(ip.visibility) LIKE CONCAT('%', LOWER(:search), '%'))
            """)
    Page<InventoryProductListResponse> getInventoryProductList(@Param("subCategoryUuid") UUID subCategoryUuid, @Param("search") String search, Pageable pageable);

}