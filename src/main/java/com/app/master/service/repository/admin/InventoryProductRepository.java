package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.InventoryProductEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InventoryProductRepository extends JpaRepository<InventoryProductEntity, Long> {

}