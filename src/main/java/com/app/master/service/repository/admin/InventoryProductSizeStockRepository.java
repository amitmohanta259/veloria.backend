package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.InventoryProductSizeStockEntity;
import com.app.master.service.core.order.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InventoryProductSizeStockRepository extends JpaRepository<InventoryProductSizeStockEntity, Long> {

    List<InventoryProductSizeStockEntity> findByProductIdAndArchiveFalseOrderByIdAsc(Long productId);

    /**
     * Adds to one size's stock in a single statement.
     *
     * The read-modify-write this replaces lost concurrent additions: two
     * restocks of +10 against 100 could both read 100 and both write 110.
     * Here the read and the write are one statement, so the row lock PostgreSQL
     * takes for the UPDATE serialises them and both additions land.
     *
     * @return rows updated — 0 means no such active size row
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE inventory_product_size_stock
               SET initial_stock = COALESCE(initial_stock, 0) + :quantity
             WHERE product_id = :productId
               AND LOWER(TRIM(size)) = LOWER(TRIM(:size))
               AND archive = false
            """)
    int addStockToSize(@Param("productId") Long productId,
                       @Param("size") String size,
                       @Param("quantity") long quantity);

    @Query(nativeQuery = true, value = """
            SELECT pss.size, pss.initial_stock,
                GREATEST(0, pss.initial_stock
                    - COALESCE(SUM(CASE
                        WHEN co.status IN (""" + OrderStatus.CONSUMING_SQL + """
                        ) AND coi.size = pss.size
                        THEN 1 END), 0)
                    + COALESCE(SUM(CASE
                        WHEN coi.return_condition IS NOT NULL
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
