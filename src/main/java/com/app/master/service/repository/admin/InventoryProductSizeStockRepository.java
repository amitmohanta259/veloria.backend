package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.InventoryProductSizeStockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InventoryProductSizeStockRepository extends JpaRepository<InventoryProductSizeStockEntity, Long> {

    List<InventoryProductSizeStockEntity> findByProductIdAndArchiveFalseOrderByIdAsc(Long productId);

    @Query(nativeQuery = true, value = """
            SELECT pss.size, pss.initial_stock,
                GREATEST(0, pss.initial_stock
                    - COALESCE(SUM(CASE
                        WHEN co.status IN ('ORDER_PLACED','PACKED','IN_TRANSIT','DISPATCHED','DONE','DELIVERED')
                             AND coi.reason_for_return IS NULL
                             AND coi.size = pss.size
                        THEN 1 END), 0)
                    + COALESCE(SUM(CASE
                        WHEN (co.status = 'RETURNED' OR coi.reason_for_return IS NOT NULL)
                             AND coi.size = pss.size
                        THEN 1 END), 0)
                ) AS current_stock
            FROM inventory_product_size_stock pss
            JOIN inventory_product ip ON ip.id = pss.product_id AND ip.archive = false
            LEFT JOIN customer_order_item coi ON coi.product_uuid = ip.uuid AND coi.archive = false
            LEFT JOIN customer_order co ON co.id = coi.customer_order_id AND co.archive = false
            WHERE pss.product_id = :productId AND pss.archive = false
            GROUP BY pss.size, pss.initial_stock
            ORDER BY ARRAY_POSITION(ARRAY['XS','S','M','L','XL','XXL','XXXL'], pss.size)
            """)
    List<Object[]> findCurrentStockByProductId(@Param("productId") Long productId);
}
