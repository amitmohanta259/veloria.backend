package com.app.master.service.repository.admin;

import com.app.master.service.core.entity.CustomerOrderItemEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CustomerOrderItemRepository extends JpaRepository<CustomerOrderItemEntity, Long> {

    List<CustomerOrderItemEntity> findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(Long customerOrderId);

    List<CustomerOrderItemEntity> findByCustomerOrderIdInAndArchiveFalseOrderByIdAsc(List<Long> orderIds);

    List<CustomerOrderItemEntity> findByUuidInAndArchiveFalse(List<UUID> uuids);

    @Query(nativeQuery = true,
        countQuery = """
            SELECT COUNT(*)
            FROM customer_order_item coi
            JOIN customer_order co ON co.id = coi.customer_order_id
            JOIN inventory_product ip ON ip.uuid = coi.product_uuid
            WHERE coi.archive = false AND co.archive = false AND ip.archive = false
              AND (:status IS NULL OR co.status = :status)
              AND (:month IS NULL OR EXTRACT(MONTH FROM co.order_placed_at) = :month)
              AND (:year  IS NULL OR EXTRACT(YEAR  FROM co.order_placed_at) = :year)
              AND (:search IS NULL
                   OR LOWER(co.customer_name) LIKE LOWER('%' || :search || '%')
                   OR LOWER(co.order_code)    LIKE LOWER('%' || :search || '%')
                   OR LOWER(ip.name)          LIKE LOWER('%' || :search || '%')
                   OR LOWER(ip.sku_id)        LIKE LOWER('%' || :search || '%'))
            """,
        value = """
            SELECT coi.uuid              AS item_uuid,
                   co.order_code,
                   ip.uuid               AS product_uuid,
                   ip.name               AS product_name,
                   ip.sku_id,
                   ip.price              AS unit_price,
                   coi.currency,
                   co.customer_name,
                   co.customer_email,
                   co.status,
                   co.order_placed_at,
                   coi.selected_dimension
            FROM customer_order_item coi
            JOIN customer_order     co ON co.id   = coi.customer_order_id
            JOIN inventory_product  ip ON ip.uuid = coi.product_uuid
            WHERE coi.archive = false AND co.archive = false AND ip.archive = false
              AND (:status IS NULL OR co.status = :status)
              AND (:month IS NULL OR EXTRACT(MONTH FROM co.order_placed_at) = :month)
              AND (:year  IS NULL OR EXTRACT(YEAR  FROM co.order_placed_at) = :year)
              AND (:search IS NULL
                   OR LOWER(co.customer_name) LIKE LOWER('%' || :search || '%')
                   OR LOWER(co.order_code)    LIKE LOWER('%' || :search || '%')
                   OR LOWER(ip.name)          LIKE LOWER('%' || :search || '%')
                   OR LOWER(ip.sku_id)        LIKE LOWER('%' || :search || '%'))
            ORDER BY co.order_placed_at DESC
            """)
    Page<Object[]> findSalesView(
            @Param("status") String status,
            @Param("month")  Integer month,
            @Param("year")   Integer year,
            @Param("search") String search,
            Pageable pageable);

    @Query(nativeQuery = true,
        countQuery = """
            SELECT COUNT(*)
            FROM customer_order_item coi
            JOIN customer_order co ON co.id = coi.customer_order_id
            JOIN inventory_product ip ON ip.uuid = coi.product_uuid
            WHERE coi.archive = false AND co.archive = false AND ip.archive = false
              AND (co.status = 'RETURNED' OR coi.reason_for_return IS NOT NULL)
              AND (:month IS NULL OR EXTRACT(MONTH FROM co.order_placed_at) = :month)
              AND (:year  IS NULL OR EXTRACT(YEAR  FROM co.order_placed_at) = :year)
              AND (:search IS NULL
                   OR LOWER(co.customer_name) LIKE LOWER('%' || :search || '%')
                   OR LOWER(co.order_code)    LIKE LOWER('%' || :search || '%')
                   OR LOWER(ip.name)          LIKE LOWER('%' || :search || '%')
                   OR LOWER(ip.sku_id)        LIKE LOWER('%' || :search || '%'))
            """,
        value = """
            SELECT coi.uuid              AS item_uuid,
                   co.order_code,
                   ip.uuid               AS product_uuid,
                   ip.name               AS product_name,
                   ip.sku_id,
                   ip.price              AS unit_price,
                   coi.currency,
                   co.customer_name,
                   co.customer_email,
                   co.status,
                   co.order_placed_at,
                   coi.selected_dimension,
                   coi.reason_for_return
            FROM customer_order_item coi
            JOIN customer_order     co ON co.id   = coi.customer_order_id
            JOIN inventory_product  ip ON ip.uuid = coi.product_uuid
            WHERE coi.archive = false AND co.archive = false AND ip.archive = false
              AND (co.status = 'RETURNED' OR coi.reason_for_return IS NOT NULL)
              AND (:month IS NULL OR EXTRACT(MONTH FROM co.order_placed_at) = :month)
              AND (:year  IS NULL OR EXTRACT(YEAR  FROM co.order_placed_at) = :year)
              AND (:search IS NULL
                   OR LOWER(co.customer_name) LIKE LOWER('%' || :search || '%')
                   OR LOWER(co.order_code)    LIKE LOWER('%' || :search || '%')
                   OR LOWER(ip.name)          LIKE LOWER('%' || :search || '%')
                   OR LOWER(ip.sku_id)        LIKE LOWER('%' || :search || '%'))
            ORDER BY co.order_placed_at DESC
            """)
    Page<Object[]> findReturnsView(
            @Param("month")  Integer month,
            @Param("year")   Integer year,
            @Param("search") String search,
            Pageable pageable);

    @Query(nativeQuery = true, value = """
            SELECT
                TO_CHAR(co.order_placed_at, 'Dy') AS label,
                EXTRACT(DOW FROM co.order_placed_at) AS sort_key,
                COUNT(coi.id) AS count
            FROM customer_order_item coi
            JOIN customer_order co ON co.id = coi.customer_order_id
            WHERE coi.archive = false AND co.archive = false
              AND (co.status = 'RETURNED' OR coi.reason_for_return IS NOT NULL)
            GROUP BY label, sort_key
            ORDER BY sort_key
            """)
    List<Object[]> findWeeklyTrends();

    @Query(nativeQuery = true, value = """
            SELECT
                TO_CHAR(co.order_placed_at, 'Mon') AS label,
                EXTRACT(MONTH FROM co.order_placed_at) AS sort_key,
                COUNT(coi.id) AS count
            FROM customer_order_item coi
            JOIN customer_order co ON co.id = coi.customer_order_id
            WHERE coi.archive = false AND co.archive = false
              AND (co.status = 'RETURNED' OR coi.reason_for_return IS NOT NULL)
              AND EXTRACT(YEAR FROM co.order_placed_at) = EXTRACT(YEAR FROM NOW())
            GROUP BY label, sort_key
            ORDER BY sort_key
            """)
    List<Object[]> findMonthlyTrends();

    @Query(nativeQuery = true, value = """
            SELECT
                reason_for_return AS reason,
                COUNT(*) AS count
            FROM customer_order_item
            WHERE archive = false
              AND reason_for_return IS NOT NULL
              AND reason_for_return <> ''
            GROUP BY reason_for_return
            ORDER BY count DESC
            LIMIT 6
            """)
    List<Object[]> findTopReturnReasons();
}
