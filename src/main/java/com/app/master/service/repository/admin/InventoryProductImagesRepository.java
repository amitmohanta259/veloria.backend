package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.InventoryProductImagesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InventoryProductImagesRepository extends JpaRepository<InventoryProductImagesEntity, Long> {

}
