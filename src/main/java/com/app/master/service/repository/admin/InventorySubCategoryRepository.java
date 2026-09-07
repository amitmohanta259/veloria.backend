package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.InventorySubCategoryEntity;
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

    @Query(nativeQuery = true, value = """
            WITH product_stocks AS (
                SELECT ip.sub_category_id,
                    COUNT(CASE WHEN co.status IN ('ORDER_PLACED','PACKED','IN_TRANSIT','DISPATCHED','DELIVERED')
                                AND coi.reason_for_return IS NULL THEN 1 END) AS sold_qty,
                    GREATEST(0, COALESCE(ip.initial_stock, 0)
                        - COUNT(CASE WHEN co.status IN ('ORDER_PLACED','PACKED','IN_TRANSIT','DISPATCHED','DELIVERED')
                                      AND coi.reason_for_return IS NULL THEN 1 END)
                        + COUNT(CASE WHEN co.status = 'RETURNED' OR coi.reason_for_return IS NOT NULL THEN 1 END)
                    ) AS current_stock
                FROM inventory_product ip
                LEFT JOIN customer_order_item coi ON coi.product_uuid = ip.uuid AND coi.archive = false
                LEFT JOIN customer_order co ON co.id = coi.customer_order_id AND co.archive = false
                WHERE ip.archive = false
                GROUP BY ip.id, ip.sub_category_id, ip.initial_stock
            )
            SELECT isc.uuid, isc.name, isc.description, isc.active, icol.name AS parent_name,
                COALESCE(SUM(ps.sold_qty), 0) AS sold_qty,
                COALESCE(SUM(ps.current_stock), 0) AS in_inventory
            FROM inventory_sub_category isc
            LEFT JOIN inventory_collection icol ON icol.id = isc.collection_id AND icol.archive = false
            LEFT JOIN product_stocks ps ON ps.sub_category_id = isc.id
            WHERE isc.archive = false
            GROUP BY isc.uuid, isc.name, isc.description, isc.active, icol.name
            ORDER BY isc.name ASC
            """)
    List<Object[]> listAllInventorySubCategoryWithStats();

    @Query(value = """
            SELECT new com.app.master.service.core.response.admin.InventoryCollectionAllResponse(
            isc.uuid, isc.name, isc.description, isc.active, i.name
            )
            FROM InventorySubCategoryEntity isc
            JOIN InventoryCollectionEntity i ON i.id = isc.collectionId
            WHERE isc.archive = false
            AND i.uuid = :collectionUuid
            ORDER BY isc.name ASC
            """)
    List<InventoryCollectionAllResponse> listAllInventorySubCategoryByCollection(@Param("collectionUuid") UUID collectionUuid);

}