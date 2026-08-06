package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.PurchaseOrderItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItemEntity, Long> {

    List<PurchaseOrderItemEntity> findByPurchaseOrderIdOrderByIdAsc(Long purchaseOrderId);
}
