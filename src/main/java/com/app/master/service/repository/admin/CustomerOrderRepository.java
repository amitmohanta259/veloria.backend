package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.CustomerOrderEntity;
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

    @Query(nativeQuery = true, value = """
            SELECT
                customer_id, customer_name, customer_email, u_phone, joining_date,
                purchases, cancellations_returns, ltv,
                CASE WHEN NOT user_active     THEN 'Inactive'
                     WHEN purchases >= 20     THEN 'VIP'
                     ELSE 'Active' END AS status
            FROM (
                SELECT
                    CAST(u.uuid AS VARCHAR) AS customer_id,
                    NULLIF(TRIM(COALESCE(u.first_name,'') || ' ' || COALESCE(u.last_name,'')), '') AS customer_name,
                    u.email AS customer_email,
                    u.phone AS u_phone,
                    COALESCE(MIN(co.order_placed_at), u.created) AS joining_date,
                    COUNT(CASE WHEN co.archive = false AND co.status NOT IN ('CANCELLED','RETURNED') THEN 1 END) AS purchases,
                    COUNT(CASE WHEN co.archive = false AND co.status IN ('CANCELLED','RETURNED') THEN 1 END) AS cancellations_returns,
                    COALESCE(SUM(CASE WHEN co.archive = false THEN co.total_value ELSE 0 END), 0) AS ltv,
                    COALESCE(u.active, true) AS user_active
                FROM users u
                LEFT JOIN customer_order co ON co.customer_id = CAST(u.uuid AS VARCHAR)
                WHERE u.archive = false
                GROUP BY u.uuid, u.first_name, u.last_name, u.email, u.phone, u.created, u.active

                UNION ALL

                SELECT
                    co.customer_id,
                    co.customer_name AS customer_name,
                    co.customer_email AS customer_email,
                    NULL AS u_phone,
                    MIN(co.order_placed_at) AS joining_date,
                    COUNT(CASE WHEN co.status NOT IN ('CANCELLED','RETURNED') THEN 1 END) AS purchases,
                    COUNT(CASE WHEN co.status IN ('CANCELLED','RETURNED') THEN 1 END) AS cancellations_returns,
                    COALESCE(SUM(co.total_value), 0) AS ltv,
                    TRUE AS user_active
                FROM customer_order co
                WHERE co.archive = false
                  AND NOT EXISTS (
                      SELECT 1 FROM users u WHERE CAST(u.uuid AS VARCHAR) = co.customer_id AND u.archive = false
                  )
                GROUP BY co.customer_id, co.customer_name, co.customer_email
            ) agg
            WHERE (:search IS NULL
                   OR LOWER(customer_name)  LIKE '%' || LOWER(:search) || '%'
                   OR LOWER(customer_email) LIKE '%' || LOWER(:search) || '%')
              AND (:status IS NULL OR
                    CASE WHEN NOT user_active THEN 'Inactive'
                         WHEN purchases >= 20 THEN 'VIP'
                         ELSE 'Active' END = :status)
            ORDER BY ltv DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM (
                SELECT CAST(u.uuid AS VARCHAR) AS customer_id
                FROM users u
                WHERE u.archive = false

                UNION ALL

                SELECT co.customer_id
                FROM customer_order co
                WHERE co.archive = false
                  AND NOT EXISTS (
                      SELECT 1 FROM users u WHERE CAST(u.uuid AS VARCHAR) = co.customer_id AND u.archive = false
                  )
                GROUP BY co.customer_id
            ) agg
            """)
    Page<Object[]> findCustomerSummaryList(@Param("search") String search,
                                           @Param("status") String status,
                                           Pageable pageable);
}
