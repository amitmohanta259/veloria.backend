package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.OrderReturnRequestEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderReturnRequestRepository extends JpaRepository<OrderReturnRequestEntity, Long> {

    Optional<OrderReturnRequestEntity> findByOrderCodeAndArchiveFalse(String orderCode);

    java.util.List<OrderReturnRequestEntity> findByOrderCodeAndArchiveFalseOrderByIdAsc(String orderCode);

    Optional<OrderReturnRequestEntity> findByReturnNumber(String returnNumber);

    java.util.List<OrderReturnRequestEntity> findByGstAdjustmentStatusOrderByIdAsc(String gstAdjustmentStatus);

    @Query("""
            SELECT r FROM OrderReturnRequestEntity r
            WHERE r.archive = false
              AND (:search IS NULL
                   OR LOWER(r.orderCode) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(r.reason) LIKE LOWER(CONCAT('%', :search, '%')))
            ORDER BY r.createdAt DESC
            """)
    Page<OrderReturnRequestEntity> findAllActive(@Param("search") String search, Pageable pageable);
}
