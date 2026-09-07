package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.PurchaseInvoiceItemEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseInvoiceItemRepository extends JpaRepository<PurchaseInvoiceItemEntity, Long> {

    List<PurchaseInvoiceItemEntity> findByPurchaseInvoiceIdOrderByLineNumberAsc(Long purchaseInvoiceId);
}
