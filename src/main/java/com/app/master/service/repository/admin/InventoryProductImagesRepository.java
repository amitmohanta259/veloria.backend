package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.InventoryProductImagesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface InventoryProductImagesRepository extends JpaRepository<InventoryProductImagesEntity, Long> {

    List<InventoryProductImagesEntity> findByProductId(Long productId);

    @Query(value = """
            SELECT ipi.image
            FROM InventoryProductImagesEntity ipi
            LEFT JOIN InventoryProductEntity ip ON ip.id = ipi.productId
            WHERE (:uuid IS NULL OR ip.uuid = :uuid)
            """)
    List<String> getInventoryProductListImage(@Param("uuid") UUID uuid);

}