package com.app.master.service.repository.client;

import com.app.master.service.core.entity.CustomerFavouriteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerFavouriteRepository extends JpaRepository<CustomerFavouriteEntity, Long> {

    @Query(nativeQuery = true, value = """
            SELECT cf.product_uuid,
                   cf.added_at,
                   ip.name            AS product_name,
                   ip.price,
                   ip.selling_price,
                   ip.price_currency,
                   ic.name            AS category_name,
                   (SELECT ipi.image FROM inventory_product_images ipi
                    WHERE ipi.product_id = ip.id ORDER BY ipi.id LIMIT 1) AS image_url,
                   (COALESCE(ip.initial_stock, 0) - COALESCE((
                       SELECT COUNT(coi.id)
                       FROM customer_order_item coi
                       JOIN customer_order co ON co.id = coi.customer_order_id
                       WHERE coi.product_uuid = ip.uuid
                         AND coi.archive = false
                         AND co.status NOT IN ('RETURNED', 'CANCELLED', 'RETURN_REQUESTED')
                   ), 0))               AS current_stock
            FROM customer_favourite cf
            JOIN inventory_product      ip   ON ip.uuid   = cf.product_uuid AND ip.archive  = false
            JOIN inventory_sub_category isc  ON isc.id    = ip.sub_category_id AND isc.archive = false
            JOIN inventory_collection   icol ON icol.id   = isc.collection_id  AND icol.archive = false
            JOIN inventory_category     ic   ON ic.id     = icol.category_id   AND ic.archive   = false
            WHERE cf.user_id = :userId AND cf.archive = false
            ORDER BY cf.added_at DESC
            """)
    List<Object[]> findFavouriteItemsWithDetails(@Param("userId") String userId);

    @Query("SELECT CAST(f.productUuid AS string) FROM CustomerFavouriteEntity f WHERE f.userId = :userId AND f.archive = false")
    List<String> findProductUuidsByUserId(@Param("userId") String userId);

    Optional<CustomerFavouriteEntity> findByUserIdAndProductUuidAndArchiveFalse(String userId, UUID productUuid);
}
