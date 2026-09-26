package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.InventoryProductEntity;
import com.app.master.service.core.order.OrderStatus;
import com.app.master.service.core.response.admin.InventoryProductListResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InventoryProductRepository extends JpaRepository<InventoryProductEntity, Long> {

    /**
     * Takes the per-product inventory lock for the duration of the transaction.
     *
     * Stock in this application is <em>derived</em> (initial − sold + returned),
     * so there is no counter to update conditionally: placing the order *is* the
     * deduction. This row is therefore used as the serialisation point — two
     * checkouts for the same product cannot both read "1 available" and both
     * insert, because the second blocks here until the first commits and then
     * re-reads with a fresh statement snapshot.
     *
     * Callers must acquire these in ascending {@code id} order; see
     * {@code ClientOrderServiceImpl.reserveInventory}.
     */
    @Query(nativeQuery = true, value = """
            SELECT id FROM inventory_product WHERE id = :productId FOR UPDATE
            """)
    Long lockForInventoryUpdate(@Param("productId") Long productId);

    /**
     * Units available for sale right now, by the application's own stock model:
     *
     * <pre>available = initial_stock − away from the shelf + handled back in</pre>
     *
     * "Away from the shelf" is every {@link OrderStatus#consumesStock()} status:
     * the whole outbound journey and the whole return journey. "Handled back in"
     * is a line with a return condition recorded against it, which is the point
     * at which someone physically received and inspected the goods.
     *
     * <p>Deliberately <em>not</em> keyed on {@code reason_for_return}: that is
     * written the moment a customer asks to return something, and crediting
     * stock then counted goods as sellable while they were still in the
     * customer's house — and, because the same field also removed the line from
     * the sold side, counted them twice over.
     *
     * <p>Two deliberate differences from the display queries:
     * <ul>
     *   <li>it sums {@code coi.quantity} rather than counting rows, so an order
     *       for five units consumes five;</li>
     *   <li>it is not clamped with {@code GREATEST(0, …)}, so an oversold state
     *       is visible as a negative number instead of being hidden as zero.</li>
     * </ul>
     */
    @Query(nativeQuery = true, value = """
            SELECT COALESCE(ip.initial_stock, 0)
                 - COALESCE((SELECT SUM(coi.quantity)
                               FROM customer_order_item coi
                               JOIN customer_order co ON co.id = coi.customer_order_id AND co.archive = false
                              WHERE coi.product_uuid = ip.uuid
                                AND coi.archive = false
                                AND co.status IN (""" + OrderStatus.CONSUMING_SQL + """
                            )), 0)
                 + COALESCE((SELECT SUM(coi.quantity)
                               FROM customer_order_item coi
                               JOIN customer_order co ON co.id = coi.customer_order_id AND co.archive = false
                              WHERE coi.product_uuid = ip.uuid
                                AND coi.archive = false
                                AND coi.return_condition IS NOT NULL), 0)
              FROM inventory_product ip
             WHERE ip.id = :productId
            """)
    Long availableStock(@Param("productId") Long productId);

    /** Adds to a sizeless product's stock in one statement. See {@code addStockToSize}. */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE inventory_product
               SET initial_stock = COALESCE(initial_stock, 0) + :quantity
             WHERE id = :productId
            """)
    int addStockToProduct(@Param("productId") Long productId,
                          @Param("quantity") long quantity);

    Optional<InventoryProductEntity> findByUuid(UUID productUuid);

    @Query(nativeQuery = true, value = """
            SELECT ip.id,
                   ip.uuid,
                   ip.name,
                   ip.description,
                   ip.price,
                   ip.price_currency,
                   ip.dimensions,
                   ic.name          AS category_name,
                   ip.selling_price
            FROM inventory_product ip
            JOIN inventory_sub_category isc  ON isc.id  = ip.sub_category_id AND isc.archive  = false
            JOIN inventory_collection   icol ON icol.id = isc.collection_id  AND icol.archive = false
            JOIN inventory_category     ic   ON ic.id   = icol.category_id   AND ic.archive   = false
            WHERE ip.archive = false
              AND ip.active  = true
              AND ip.draft   = false
              AND ip.created >= :since
            ORDER BY ip.created DESC
            """)
    List<Object[]> findNewArrivals(@Param("since") Instant since);

    @Query(nativeQuery = true, value = """
            SELECT ip.id,
                   ip.uuid,
                   ip.name,
                   ip.description,
                   ip.price,
                   ip.price_currency,
                   ip.dimensions,
                   ic.name AS category_name,
                   ip.selling_price
            FROM inventory_product ip
            JOIN inventory_sub_category isc  ON isc.id  = ip.sub_category_id AND isc.archive  = false
            JOIN inventory_collection   icol ON icol.id = isc.collection_id  AND icol.archive = false
            JOIN inventory_category     ic   ON ic.id   = icol.category_id   AND ic.archive   = false
            WHERE ip.archive = false
              AND ip.active  = true
              AND ip.draft   = false
            ORDER BY ip.created DESC
            """)
    List<Object[]> findAllProductsForClient();

    @Query(nativeQuery = true, value = """
            SELECT ip.id,
                   ip.uuid,
                   ip.name,
                   ip.description,
                   ip.price,
                   ip.price_currency,
                   ip.dimensions,
                   ic.name  AS category_name,
                   ip.selling_price,
                   icol.name AS collection_name
            FROM inventory_product ip
            JOIN inventory_sub_category isc  ON isc.id  = ip.sub_category_id AND isc.archive  = false
            JOIN inventory_collection   icol ON icol.id = isc.collection_id  AND icol.archive = false
            JOIN inventory_category     ic   ON ic.id   = icol.category_id   AND ic.archive   = false
            WHERE ip.uuid    = :uuid
              AND ip.archive = false
              AND ip.active  = true
              AND ip.draft   = false
            """)
    List<Object[]> findProductByUuidForClient(@Param("uuid") UUID uuid);

    List<InventoryProductEntity> findByUuidInAndArchiveFalse(List<UUID> uuids);

    @Query(value = """
            SELECT new com.app.master.service.core.response.admin.InventoryProductListResponse(
            ip.uuid, ip.name, ip.description, ip.skuId, ip.price, ip.sellingPrice, ip.priceCurrency,
            ip.initialStock, ip.visibility, ip.gender, ip.active, ip.dimensions, ip.supplierUuid
            )
            FROM InventoryProductEntity ip
            WHERE ip.archive = false
            AND (:search IS NULL
                 OR LOWER(ip.name) LIKE CONCAT('%', LOWER(:search), '%')
                 OR LOWER(ip.skuId) LIKE CONCAT('%', LOWER(:search), '%'))
            ORDER BY ip.created DESC
            """)
    Page<InventoryProductListResponse> getInventoryProductList(@Param("search") String search, Pageable pageable);

    @Query(value = """
            SELECT new com.app.master.service.core.response.admin.InventoryProductListResponse(
            ip.uuid, ip.name, ip.description, ip.skuId, ip.price, ip.sellingPrice, ip.priceCurrency,
            ip.initialStock, ip.visibility, ip.gender, ip.active, ip.dimensions, ip.supplierUuid
            )
            FROM InventoryProductEntity ip
            JOIN InventorySubCategoryEntity isc ON isc.id = ip.subCategoryId
            WHERE ip.archive = false
            AND isc.uuid = :subCategoryUuid
            AND (:search IS NULL
                 OR LOWER(ip.name) LIKE CONCAT('%', LOWER(:search), '%')
                 OR LOWER(ip.skuId) LIKE CONCAT('%', LOWER(:search), '%'))
            ORDER BY ip.created DESC
            """)
    Page<InventoryProductListResponse> getInventoryProductListBySubCategory(@Param("subCategoryUuid") UUID subCategoryUuid, @Param("search") String search, Pageable pageable);

    @Query(nativeQuery = true, value = """
            SELECT
                ic.name                                              AS category,
                icol.name                                            AS collection,
                isc.name                                             AS sub_category,
                ip.name                                              AS product_name,
                ip.uuid                                              AS product_uuid,
                COUNT(CASE
                    WHEN coi.id IS NOT NULL
                         AND co.status IN (""" + OrderStatus.CONSUMING_SQL + """
                    ) THEN 1 END)                                    AS in_sold,
                COUNT(CASE
                    WHEN coi.id IS NOT NULL
                         AND coi.return_condition = 'PRODUCT_OK'
                    THEN 1 END)                                      AS returned,
                COUNT(CASE
                    WHEN coi.id IS NOT NULL
                         AND coi.return_condition IN ('DAMAGED', 'LOST')
                    THEN 1 END)                                      AS damaged,
                GREATEST(0,
                    COALESCE(ip.initial_stock, 0)
                    - COUNT(CASE
                        WHEN coi.id IS NOT NULL
                             AND co.status IN (""" + OrderStatus.CONSUMING_SQL + """
                        ) THEN 1 END)
                    + COUNT(CASE
                        WHEN coi.id IS NOT NULL
                             AND coi.return_condition IS NOT NULL
                        THEN 1 END)
                )                                                    AS in_inventory
            FROM inventory_product ip
            JOIN inventory_sub_category isc  ON isc.id   = ip.sub_category_id  AND isc.archive  = false
            JOIN inventory_collection   icol ON icol.id  = isc.collection_id   AND icol.archive = false
            JOIN inventory_category     ic   ON ic.id    = icol.category_id    AND ic.archive   = false
            LEFT JOIN customer_order_item coi ON coi.product_uuid = ip.uuid    AND coi.archive  = false
            LEFT JOIN customer_order      co  ON co.id = coi.customer_order_id  AND co.archive   = false
            WHERE ip.archive = false
            GROUP BY ic.name, icol.name, isc.name, ip.name, ip.uuid, ip.initial_stock
            ORDER BY ic.name, icol.name, isc.name, ip.name
            """)
    List<Object[]> findPerformanceLedger();

    @Query(nativeQuery = true, value = """
            SELECT ip.id,
                   ip.uuid,
                   ip.name,
                   ip.description,
                   ip.price,
                   ip.price_currency,
                   ip.dimensions,
                   ic.name         AS category_name,
                   ip.selling_price,
                   COUNT(coi.id)   AS demand_count
            FROM inventory_product ip
            JOIN inventory_sub_category isc  ON isc.id  = ip.sub_category_id AND isc.archive  = false
            JOIN inventory_collection   icol ON icol.id = isc.collection_id  AND icol.archive = false
            JOIN inventory_category     ic   ON ic.id   = icol.category_id   AND ic.archive   = false
            LEFT JOIN customer_order_item coi ON coi.product_uuid = ip.uuid AND coi.archive = false
            LEFT JOIN customer_order      co  ON co.id = coi.customer_order_id AND co.archive = false
                 AND co.status NOT IN ('RETURNED','CANCELLED') AND coi.reason_for_return IS NULL
            WHERE ip.archive = false
              AND ip.active  = true
              AND ip.draft   = false
            GROUP BY ip.id, ip.uuid, ip.name, ip.description, ip.price, ip.price_currency,
                     ip.dimensions, ic.name, ip.selling_price, ip.created
            ORDER BY demand_count DESC, ip.created DESC
            LIMIT :limit OFFSET :offset
            """)
    List<Object[]> findPopularProducts(@Param("limit") int limit, @Param("offset") int offset);

    @Query(nativeQuery = true, value = """
            SELECT ic.name AS category_name,
                   (SELECT ipi.image
                    FROM inventory_product_images ipi
                    JOIN inventory_product ip2 ON ip2.id = ipi.product_id AND ip2.archive = false
                       AND ip2.active = true AND ip2.draft = false
                    JOIN inventory_sub_category isc2 ON isc2.id = ip2.sub_category_id AND isc2.archive = false
                    JOIN inventory_collection icol2 ON icol2.id = isc2.collection_id AND icol2.archive = false
                    WHERE icol2.category_id = ic.id
                    ORDER BY ip2.created DESC, ipi.id ASC
                    LIMIT 1) AS image_url
            FROM inventory_category ic
            WHERE ic.archive = false
            ORDER BY ic.name
            """)
    List<Object[]> findCategoriesWithLatestImage();

    @Query(nativeQuery = true, value = """
            WITH current_stocks AS (
                SELECT
                    ip.id,
                    ip.price,
                    ip.price_currency,
                    GREATEST(0,
                        COALESCE(ip.initial_stock, 0)
                        - COUNT(CASE WHEN co.status IN (""" + OrderStatus.CONSUMING_SQL + """
                                 ) THEN 1 END)
                        + COUNT(CASE WHEN coi.return_condition IS NOT NULL THEN 1 END)
                    ) AS current_stock
                FROM inventory_product ip
                LEFT JOIN customer_order_item coi ON coi.product_uuid = ip.uuid AND coi.archive = false
                LEFT JOIN customer_order co ON co.id = coi.customer_order_id AND co.archive = false
                WHERE ip.archive = false
                GROUP BY ip.id, ip.price, ip.price_currency, ip.initial_stock
            )
            SELECT
                COALESCE(SUM(price * current_stock), 0)                                     AS total_stock_value,
                COALESCE(MAX(price_currency), 'INR')                                        AS currency,
                COUNT(CASE WHEN current_stock > 0 AND current_stock <= 20 THEN 1 END)       AS low_stock_count,
                COUNT(CASE WHEN current_stock = 0 THEN 1 END)                               AS out_of_stock_count
            FROM current_stocks
            """)
    List<Object[]> findInventoryStats();

    @Query(nativeQuery = true, value = """
            SELECT
                ip.uuid,
                GREATEST(0,
                    COALESCE(ip.initial_stock, 0)
                    - COUNT(CASE WHEN co.status IN (""" + OrderStatus.CONSUMING_SQL + """
                             ) THEN 1 END)
                    + COUNT(CASE WHEN coi.return_condition IS NOT NULL THEN 1 END)
                ) AS current_stock
            FROM inventory_product ip
            LEFT JOIN customer_order_item coi ON coi.product_uuid = ip.uuid AND coi.archive = false
            LEFT JOIN customer_order co ON co.id = coi.customer_order_id AND co.archive = false
            WHERE ip.uuid IN :uuids AND ip.archive = false
            GROUP BY ip.uuid, ip.initial_stock
            """)
    List<Object[]> findCurrentStockByUuids(@Param("uuids") List<UUID> uuids);

    @Query(nativeQuery = true, value = """
            SELECT ic.name                                                             AS category,
                   COUNT(coi.id)                                                      AS sales,
                   ROUND(COUNT(coi.id) * 100.0 / NULLIF(SUM(COUNT(coi.id)) OVER(),0),1) AS share
            FROM inventory_category ic
            JOIN inventory_collection   icol ON icol.category_id  = ic.id   AND icol.archive = false
            JOIN inventory_sub_category isc  ON isc.collection_id = icol.id AND isc.archive  = false
            JOIN inventory_product      ip   ON ip.sub_category_id= isc.id  AND ip.archive   = false
            LEFT JOIN customer_order_item coi ON coi.product_uuid = ip.uuid AND coi.archive  = false
            LEFT JOIN customer_order      co  ON co.id = coi.customer_order_id AND co.archive = false
                 AND co.status NOT IN ('RETURNED','CANCELLED') AND coi.reason_for_return IS NULL
            WHERE ic.archive = false
            GROUP BY ic.name
            ORDER BY sales DESC
            LIMIT 1
            """)
    List<Object[]> findTopCategory();

    @Query(nativeQuery = true, value = """
            SELECT ip.name,
                   ip.price_currency,
                   COUNT(coi.id)                                                      AS sales_count,
                   COUNT(coi.id) * COALESCE(ip.selling_price, ip.price)               AS total_revenue,
                   (SELECT ipi2.image FROM inventory_product_images ipi2
                    WHERE ipi2.product_id = ip.id ORDER BY ipi2.id LIMIT 1)           AS image_url
            FROM inventory_product ip
            LEFT JOIN customer_order_item coi ON coi.product_uuid = ip.uuid AND coi.archive = false
            LEFT JOIN customer_order      co  ON co.id = coi.customer_order_id AND co.archive = false
                 AND co.status NOT IN ('RETURNED','CANCELLED') AND coi.reason_for_return IS NULL
                 AND EXTRACT(MONTH FROM co.order_placed_at) = EXTRACT(MONTH FROM NOW())
                 AND EXTRACT(YEAR  FROM co.order_placed_at) = EXTRACT(YEAR  FROM NOW())
            WHERE ip.archive = false
            GROUP BY ip.id, ip.name, ip.price_currency, ip.selling_price, ip.price
            ORDER BY sales_count DESC
            LIMIT 3
            """)
    List<Object[]> findTopSellersMonthly();

    @Query(nativeQuery = true, value = """
            SELECT ip.name,
                   ip.price_currency,
                   COUNT(coi.id)                                                      AS sales_count,
                   COUNT(coi.id) * COALESCE(ip.selling_price, ip.price)               AS total_revenue,
                   (SELECT ipi2.image FROM inventory_product_images ipi2
                    WHERE ipi2.product_id = ip.id ORDER BY ipi2.id LIMIT 1)           AS image_url
            FROM inventory_product ip
            LEFT JOIN customer_order_item coi ON coi.product_uuid = ip.uuid AND coi.archive = false
            LEFT JOIN customer_order      co  ON co.id = coi.customer_order_id AND co.archive = false
                 AND co.status NOT IN ('RETURNED','CANCELLED') AND coi.reason_for_return IS NULL
                 AND co.order_placed_at >= DATE_TRUNC('quarter', NOW())
            WHERE ip.archive = false
            GROUP BY ip.id, ip.name, ip.price_currency, ip.selling_price, ip.price
            ORDER BY sales_count DESC
            LIMIT 3
            """)
    List<Object[]> findTopSellersQuarterly();

    @Query(nativeQuery = true, value = """
            SELECT ip.name,
                   ip.price_currency,
                   COUNT(coi.id)                                                      AS sales_count,
                   COUNT(coi.id) * COALESCE(ip.selling_price, ip.price)               AS total_revenue,
                   (SELECT ipi2.image FROM inventory_product_images ipi2
                    WHERE ipi2.product_id = ip.id ORDER BY ipi2.id LIMIT 1)           AS image_url
            FROM inventory_product ip
            LEFT JOIN customer_order_item coi ON coi.product_uuid = ip.uuid AND coi.archive = false
            LEFT JOIN customer_order      co  ON co.id = coi.customer_order_id AND co.archive = false
                 AND co.status NOT IN ('RETURNED','CANCELLED') AND coi.reason_for_return IS NULL
                 AND EXTRACT(YEAR FROM co.order_placed_at) = EXTRACT(YEAR FROM NOW())
            WHERE ip.archive = false
            GROUP BY ip.id, ip.name, ip.price_currency, ip.selling_price, ip.price
            ORDER BY sales_count DESC
            LIMIT 3
            """)
    List<Object[]> findTopSellersAnnual();
}
