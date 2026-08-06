package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.CustomerOrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerOrderRepository extends JpaRepository<CustomerOrderEntity, Long> {

    Optional<CustomerOrderEntity> findByUuid(UUID uuid);

    @Query("""
            SELECT o FROM CustomerOrderEntity o
            WHERE o.customerId = :customerId
              AND o.archive = false
            ORDER BY o.orderPlacedAt DESC
            """)
    Page<CustomerOrderEntity> findByCustomerId(@Param("customerId") String customerId, Pageable pageable);

    long countByCustomerIdAndArchiveFalse(String customerId);

    long countByStatusAndArchiveFalse(String status);

    long countByArchiveFalse();

    @Query("SELECT COALESCE(SUM(o.totalValue), 0) FROM CustomerOrderEntity o WHERE o.customerId = :customerId AND o.archive = false")
    Long sumTotalValueByCustomerId(@Param("customerId") String customerId);

    @Query("SELECT COALESCE(SUM(o.totalValue), 0) FROM CustomerOrderEntity o WHERE o.archive = false")
    Long sumAllTotalValue();

    @Query("SELECT COALESCE(SUM(o.totalValue), 0) FROM CustomerOrderEntity o WHERE o.status = 'RETURNED' AND o.archive = false")
    Long sumReturnedTotalValue();
}
