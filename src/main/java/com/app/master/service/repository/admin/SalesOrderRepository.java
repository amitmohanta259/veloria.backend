package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.SalesOrderEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    // ─── Analytics queries ──────────────────────────────────────────────────

    @Query(nativeQuery = true, value = """
            SELECT COALESCE(SUM(total_value), 0)
            FROM sales_order
            WHERE archive = false AND EXTRACT(YEAR FROM order_placed_at) = :year
            """)
    Long sumRevenueByYear(@Param("year") int year);

    @Query(nativeQuery = true, value = """
            SELECT COUNT(*) FROM sales_order
            WHERE archive = false AND EXTRACT(YEAR FROM order_placed_at) = :year
            """)
    Long countOrdersByYear(@Param("year") int year);

    @Query(nativeQuery = true, value = """
            SELECT COALESCE(SUM(total_value), 0)
            FROM sales_order
            WHERE archive = false
              AND EXTRACT(YEAR FROM order_placed_at) = :year
              AND EXTRACT(QUARTER FROM order_placed_at) = :quarter
            """)
    Long sumRevenueByYearAndQuarter(@Param("year") int year, @Param("quarter") int quarter);

    @Query(nativeQuery = true, value = """
            SELECT COUNT(*) FROM sales_order
            WHERE archive = false
              AND EXTRACT(YEAR FROM order_placed_at) = :year
              AND EXTRACT(QUARTER FROM order_placed_at) = :quarter
            """)
    Long countOrdersByYearAndQuarter(@Param("year") int year, @Param("quarter") int quarter);

    @Query(nativeQuery = true, value = """
            SELECT COALESCE(SUM(total_value), 0)
            FROM sales_order
            WHERE archive = false
              AND EXTRACT(YEAR FROM order_placed_at) = :year
              AND EXTRACT(MONTH FROM order_placed_at) = :month
            """)
    Long sumRevenueByYearAndMonth(@Param("year") int year, @Param("month") int month);

    @Query(nativeQuery = true, value = """
            SELECT COUNT(*) FROM sales_order
            WHERE archive = false
              AND EXTRACT(YEAR FROM order_placed_at) = :year
              AND EXTRACT(MONTH FROM order_placed_at) = :month
            """)
    Long countOrdersByYearAndMonth(@Param("year") int year, @Param("month") int month);

    @Query(nativeQuery = true, value = """
            SELECT
                CAST(EXTRACT(YEAR FROM order_placed_at) AS INTEGER)  AS year,
                CAST(EXTRACT(MONTH FROM order_placed_at) AS INTEGER) AS month,
                COALESCE(SUM(total_value), 0)                         AS revenue,
                COUNT(*)                                               AS orders
            FROM sales_order
            WHERE archive = false
              AND EXTRACT(YEAR FROM order_placed_at) IN (:year1, :year2)
            GROUP BY 1, 2
            ORDER BY 1, 2
            """)
    List<Object[]> findMonthlyChartData(@Param("year1") int year1, @Param("year2") int year2);

    @Query(nativeQuery = true, value = """
            SELECT
                CAST(EXTRACT(YEAR FROM order_placed_at) AS INTEGER)    AS year,
                CAST(EXTRACT(QUARTER FROM order_placed_at) AS INTEGER) AS quarter,
                COALESCE(SUM(total_value), 0)                           AS revenue,
                COUNT(*)                                                 AS orders
            FROM sales_order
            WHERE archive = false
              AND EXTRACT(YEAR FROM order_placed_at) IN (:year1, :year2)
            GROUP BY 1, 2
            ORDER BY 1, 2
            """)
    List<Object[]> findQuarterlyChartData(@Param("year1") int year1, @Param("year2") int year2);

    @Query(nativeQuery = true, value = """
            SELECT
                ic.name                          AS category,
                COALESCE(SUM(so.total_value), 0) AS revenue
            FROM sales_order so
            JOIN inventory_product      ip   ON ip.uuid = so.product_uuid AND ip.archive = false
            JOIN inventory_sub_category isc  ON isc.id = ip.sub_category_id AND isc.archive = false
            JOIN inventory_collection   icol ON icol.id = isc.collection_id AND icol.archive = false
            JOIN inventory_category     ic   ON ic.id = icol.category_id AND ic.archive = false
            WHERE so.archive = false
            GROUP BY ic.name
            ORDER BY revenue DESC
            """)
    List<Object[]> findRevenueByCategory();

    @Query(nativeQuery = true, value = """
            SELECT
                CAST(EXTRACT(YEAR FROM order_placed_at) AS INTEGER)  AS year,
                CAST(EXTRACT(MONTH FROM order_placed_at) AS INTEGER) AS month,
                COALESCE(SUM(total_value), 0)                         AS revenue,
                COUNT(*)                                               AS orders
            FROM sales_order
            WHERE archive = false
            GROUP BY 1, 2
            ORDER BY 1 DESC, 2 DESC
            """)
    List<Object[]> findMonthlySummary();

    @Query(nativeQuery = true, value = """
            SELECT DISTINCT CAST(EXTRACT(YEAR FROM order_placed_at) AS INTEGER) AS year
            FROM sales_order
            WHERE archive = false
            ORDER BY year DESC
            """)
    List<Integer> findDistinctOrderYears();

    // ─── Dashboard queries ──────────────────────────────────────────────────

    @Query(nativeQuery = true, value = """
            SELECT COUNT(*) FROM sales_order
            WHERE archive = false
              AND status NOT IN ('DELIVERED', 'RETURNED', 'CANCELLED')
            """)
    Long countActiveOrders();

    @Query(nativeQuery = true, value = """
            SELECT ROUND(
                COUNT(CASE WHEN status = 'RETURNED' THEN 1 END) * 100.0
                / NULLIF(COUNT(*), 0), 1)
            FROM sales_order WHERE archive = false
            """)
    Double findReturnRate();

    @Query("""
            SELECT s FROM SalesOrderEntity s
            WHERE s.archive = false
              AND s.status NOT IN ('CANCELLED', 'RETURNED')
              AND (:status IS NULL OR s.status = :status)
            ORDER BY s.orderPlacedAt DESC
            """)
    Page<SalesOrderEntity> findRecentOrders(@Param("status") String status, Pageable pageable);
}
