package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.SalesOrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SalesOrderRepository extends JpaRepository<SalesOrderEntity, Long> {

    long countByArchiveFalse();

    long countByStatusAndArchiveFalse(String status);

    Optional<SalesOrderEntity> findByUuid(UUID uuid);

    @Query("""
            SELECT SUM(s.totalValue) FROM SalesOrderEntity s
            WHERE s.archive = false
            """)
    Long sumTotalRevenue();

    @Query("""
            SELECT s FROM SalesOrderEntity s
            WHERE s.archive = false
              AND (:status IS NULL OR s.status = :status)
              AND (:month IS NULL OR extract(month from s.orderPlacedAt) = :month)
              AND (:year IS NULL OR extract(year from s.orderPlacedAt) = :year)
              AND (:search IS NULL
                   OR LOWER(s.customerName) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(s.productName) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(s.orderCode) LIKE LOWER(CONCAT('%', :search, '%')))
            ORDER BY s.orderPlacedAt DESC
            """)
    Page<SalesOrderEntity> allOrders(@Param("status") String status,
                                      @Param("month") Integer month,
                                      @Param("year") Integer year,
                                      @Param("search") String search,
                                      Pageable pageable);
}
