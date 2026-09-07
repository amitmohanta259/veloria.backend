package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.OrderReturnItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderReturnItemRepository extends JpaRepository<OrderReturnItemEntity, Long> {

    List<OrderReturnItemEntity> findByReturnRequestIdOrderByIdAsc(Long returnRequestId);

    List<OrderReturnItemEntity> findByOrderItemIdOrderByIdAsc(Long orderItemId);

    /** Units already returned for an order item across every prior return. */
    @Query("SELECT COALESCE(SUM(r.quantity), 0) FROM OrderReturnItemEntity r WHERE r.orderItemId = :orderItemId")
    int totalReturnedQuantity(@Param("orderItemId") Long orderItemId);

    /** GST already reversed for an order item, so the last return settles exactly. */
    @Query("SELECT COALESCE(SUM(r.taxableValuePaise), 0) FROM OrderReturnItemEntity r WHERE r.orderItemId = :orderItemId")
    long totalReversedTaxable(@Param("orderItemId") Long orderItemId);

    @Query("SELECT COALESCE(SUM(r.cgstAmountPaise), 0) FROM OrderReturnItemEntity r WHERE r.orderItemId = :orderItemId")
    long totalReversedCgst(@Param("orderItemId") Long orderItemId);

    @Query("SELECT COALESCE(SUM(r.sgstAmountPaise), 0) FROM OrderReturnItemEntity r WHERE r.orderItemId = :orderItemId")
    long totalReversedSgst(@Param("orderItemId") Long orderItemId);

    @Query("SELECT COALESCE(SUM(r.igstAmountPaise), 0) FROM OrderReturnItemEntity r WHERE r.orderItemId = :orderItemId")
    long totalReversedIgst(@Param("orderItemId") Long orderItemId);
}
