package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.PurchaseOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrderEntity, Long> {

    Optional<PurchaseOrderEntity> findByUuid(UUID uuid);

    List<PurchaseOrderEntity> findBySupplierUuidAndArchiveFalseOrderByCreatedDesc(UUID supplierUuid);

    List<PurchaseOrderEntity> findByArchiveFalseOrderByCreatedDesc();
}
