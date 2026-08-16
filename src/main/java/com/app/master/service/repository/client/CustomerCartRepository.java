package com.app.master.service.repository.client;

import com.app.master.service.core.entity.CustomerCartEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerCartRepository extends JpaRepository<CustomerCartEntity, Long> {

    @Query(nativeQuery = true, value = """
            SELECT cct.product_uuid,
                   cct.quantity,
                   cct.added_at,
                   ip.name            AS product_name,
                   ip.price,
                   ip.selling_price,
                   ip.price_currency,
                   ic.name            AS category_name,
                   (SELECT ipi.image FROM inventory_product_images ipi
                    WHERE ipi.product_id = ip.id ORDER BY ipi.id LIMIT 1) AS image_url
            FROM customer_cart cct
            JOIN inventory_product      ip   ON ip.uuid   = cct.product_uuid AND ip.archive  = false
            JOIN inventory_sub_category isc  ON isc.id    = ip.sub_category_id AND isc.archive = false
            JOIN inventory_collection   icol ON icol.id   = isc.collection_id  AND icol.archive = false
            JOIN inventory_category     ic   ON ic.id     = icol.category_id   AND ic.archive   = false
            WHERE cct.user_id = :userId AND cct.archive = false
            ORDER BY cct.added_at DESC
            """)
    List<Object[]> findCartItemsWithDetails(@Param("userId") String userId);

    Optional<CustomerCartEntity> findByUserIdAndProductUuidAndArchiveFalse(String userId, UUID productUuid);

    List<CustomerCartEntity> findByUserIdAndArchiveFalse(String userId);
}
