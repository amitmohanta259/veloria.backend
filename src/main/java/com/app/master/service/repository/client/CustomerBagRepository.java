package com.app.master.service.repository.client;

import com.app.master.service.core.entity.CustomerBagEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerBagRepository extends JpaRepository<CustomerBagEntity, Long> {

    @Query(nativeQuery = true, value = """
            SELECT cb.product_uuid,
                   cb.quantity,
                   cb.added_at,
                   ip.name           AS product_name,
                   ip.price,
                   ip.selling_price,
                   ip.price_currency,
                   ic.name           AS category_name,
                   (SELECT ipi.image FROM inventory_product_images ipi
                    WHERE ipi.product_id = ip.id ORDER BY ipi.id LIMIT 1) AS image_url,
                   CASE
                     WHEN cb.size IS NOT NULL THEN
                       GREATEST(0,
                         COALESCE((SELECT pss.initial_stock FROM inventory_product_size_stock pss
                                   WHERE pss.product_id = ip.id AND pss.size = cb.size AND pss.archive = false LIMIT 1), 0)
                         - COALESCE((SELECT COUNT(*) FROM customer_order_item coi
                                     JOIN customer_order co ON co.id = coi.customer_order_id
                                     WHERE coi.product_uuid = ip.uuid AND coi.size = cb.size
                                       AND coi.archive = false
                                       AND co.status NOT IN ('RETURNED','CANCELLED','RETURN_REQUESTED')), 0))
                     ELSE
                       GREATEST(0, COALESCE(ip.initial_stock, 0) - COALESCE((
                           SELECT COUNT(coi.id) FROM customer_order_item coi
                           JOIN customer_order co ON co.id = coi.customer_order_id
                           WHERE coi.product_uuid = ip.uuid AND coi.archive = false
                             AND co.status NOT IN ('RETURNED','CANCELLED','RETURN_REQUESTED')
                       ), 0))
                   END AS current_stock,
                   cb.size
            FROM customer_bag cb
            JOIN inventory_product      ip   ON ip.uuid  = cb.product_uuid AND ip.archive  = false
            JOIN inventory_sub_category isc  ON isc.id   = ip.sub_category_id AND isc.archive = false
            JOIN inventory_collection   icol ON icol.id  = isc.collection_id  AND icol.archive = false
            JOIN inventory_category     ic   ON ic.id    = icol.category_id   AND ic.archive   = false
            WHERE cb.user_id = :userId AND cb.archive = false
            ORDER BY cb.added_at DESC
            """)
    List<Object[]> findBagItemsWithDetails(@Param("userId") String userId);

    Optional<CustomerBagEntity> findByUserIdAndProductUuidAndSizeAndArchiveFalse(String userId, UUID productUuid, String size);

    Optional<CustomerBagEntity> findFirstByUserIdAndProductUuidAndArchiveFalse(String userId, UUID productUuid);

    List<CustomerBagEntity> findAllByUserIdAndArchiveFalse(String userId);

    @Query(nativeQuery = true, value = """
            SELECT CAST(cb.product_uuid AS text), cb.quantity, ip.selling_price, ip.hsn_code, cb.size
            FROM customer_bag cb
            JOIN inventory_product ip ON ip.uuid = cb.product_uuid AND ip.archive = false
            WHERE cb.user_id = :userId AND cb.archive = false
            """)
    List<Object[]> findBagItemsForGst(@Param("userId") String userId);
}
