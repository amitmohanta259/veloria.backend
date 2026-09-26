package com.app.master.service.order;

import com.app.master.service.service.admin.SalesOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Who may move an order.
 *
 * <p>Until P0-5A {@code /api/master/sales-order/**} sat on the application's
 * permissive default chain: no token was parsed and no method check applied, so
 * anyone who could reach the service could mark an order delivered or cancel
 * it. These tests pin the three outcomes — anonymous, authenticated but without
 * the authority, and authorised — at the service boundary, which is where the
 * check now lives so that a future non-HTTP caller cannot slip past it.
 */
@SpringBootTest(properties = {
        "AWS_ACCESS_KEY=test-placeholder-not-a-credential",
        "AWS_SECRET_KEY=test-placeholder-not-a-credential",
        // One shared pool size across every integration test, on purpose.
        //
        // Spring caches a context per distinct property set, and each context
        // brings its own connection pool. Five different sizes meant five pools
        // — 160 connections against a server that allows 100, so a suite run
        // died with "too many clients" while each test passed alone. Identical
        // properties mean the contexts are shared, and 40 is enough for the
        // largest concurrency test now that placing an order also books its sale.
        "spring.datasource.hikari.maximum-pool-size=40"
})
class OrderLifecycleAuthorizationTest {

    @Autowired private SalesOrderService salesOrderService;
    @Autowired private JdbcTemplate jdbc;

    private String orderCode;
    private String buyerUuid;
    private Long productId;

    @BeforeEach
    void seedOrder() {
        buyerUuid = UUID.randomUUID().toString();
        String email = "authz-" + buyerUuid.substring(0, 8) + "@automation.veloria.test";
        jdbc.update("""
                INSERT INTO users (uuid, first_name, last_name, email, phone, active, archive, created)
                VALUES (?::uuid, 'Automation', 'Buyer', ?, '9000000000', true, false, now())
                """, buyerUuid, email);

        UUID productUuid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_product (uuid, name, sku_id, price, selling_price, price_currency,
                                               initial_stock, hsn_code, archive, created)
                VALUES (?::uuid, 'Automation Authz Product', ?, 100000, 100000, 'INR', 5, '6211', false, now())
                """, productUuid.toString(), "AUTO-AZ-" + productUuid.toString().substring(0, 8));
        productId = jdbc.queryForObject(
                "SELECT id FROM inventory_product WHERE uuid = ?::uuid", Long.class, productUuid.toString());

        orderCode = "VO-AZ-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("""
                INSERT INTO customer_order (uuid, order_code, customer_id, customer_email, delivery_location,
                                            currency, total_value, status, order_placed_at, active, archive)
                VALUES (gen_random_uuid(), ?, ?, ?, '12 MG Road, Bengaluru', 'INR', 100000, 'ORDER_PLACED',
                        timezone('UTC', now()), true, false)
                """, orderCode, buyerUuid, email);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM customer_order WHERE order_code = ?", Long.class, orderCode);
        jdbc.update("""
                INSERT INTO customer_order_item (uuid, customer_order_id, product_uuid, quantity, archive)
                VALUES (gen_random_uuid(), ?, ?::uuid, 1, false)
                """, orderId, productUuid.toString());
    }

    @AfterEach
    void cleanUp() {
        Long id = jdbc.query("SELECT id FROM customer_order WHERE order_code = ?",
                rs -> rs.next() ? rs.getLong(1) : null, orderCode);
        if (id != null) {
            jdbc.update("DELETE FROM customer_order_item WHERE customer_order_id = ?", id);
            jdbc.update("DELETE FROM customer_order WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM inventory_product WHERE id = ?", productId);
        jdbc.update("DELETE FROM users WHERE uuid = ?::uuid", buyerUuid);
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM customer_order WHERE order_code = ?", String.class, orderCode);
    }

    // ── anonymous ────────────────────────────────────────────────────────────

    @Test
    @WithAnonymousUser
    @DisplayName("an anonymous caller cannot advance an order")
    void anonymousCannotAdvance() {
        assertThrows(Exception.class, () -> salesOrderService.updateOrderStatus(orderCode, "PACKED"));
        assertEquals("ORDER_PLACED", status(), "nothing may have changed");
    }

    @Test
    @WithAnonymousUser
    @DisplayName("an anonymous caller cannot cancel an order")
    void anonymousCannotCancel() {
        assertThrows(Exception.class, () -> salesOrderService.cancelOrder(orderCode, "not mine to cancel"));
        assertEquals("ORDER_PLACED", status());
        assertNull(jdbc.queryForObject("SELECT cancel_reason FROM customer_order WHERE order_code = ?",
                String.class, orderCode), "no reason may have been recorded");
    }

    @Test
    @DisplayName("a caller with no security context at all is refused")
    void noContextIsRefused() {
        // The shape a background job or another service would arrive in.
        assertThrows(Exception.class, () -> salesOrderService.updateOrderStatus(orderCode, "PACKED"));
        assertEquals("ORDER_PLACED", status());
    }

    // ── authenticated, but not authorised ────────────────────────────────────

    @Test
    @WithMockUser(authorities = "VIEW_GST")
    @DisplayName("read access does not carry the right to move an order")
    void viewerCannotAdvance() {
        assertThrows(AccessDeniedException.class,
                () -> salesOrderService.updateOrderStatus(orderCode, "PACKED"));
        assertEquals("ORDER_PLACED", status());
    }

    @Test
    @WithMockUser(authorities = {"VIEW_GST", "CREATE_INVOICE", "PREPARE_GSTR1"})
    @DisplayName("other GST permissions do not stand in for the administrator authority")
    void otherPermissionsDoNotSuffice() {
        assertThrows(AccessDeniedException.class,
                () -> salesOrderService.updateOrderStatus(orderCode, "PACKED"));
        assertThrows(AccessDeniedException.class,
                () -> salesOrderService.cancelOrder(orderCode, "nope"));
        assertEquals("ORDER_PLACED", status());
    }

    // ── authorised ───────────────────────────────────────────────────────────

    @Test
    @WithMockUser(authorities = "ADMIN_GST")
    @DisplayName("an administrator can advance an order")
    void adminCanAdvance() throws Exception {
        salesOrderService.updateOrderStatus(orderCode, "PACKED");
        assertEquals("PACKED", status());
    }

    @Test
    @WithMockUser(authorities = "ADMIN_GST")
    @DisplayName("an administrator can cancel an order, and the reason is recorded")
    void adminCanCancel() throws Exception {
        salesOrderService.cancelOrder(orderCode, "out of stock at the warehouse");
        assertEquals("CANCELLED", status());
        assertEquals("out of stock at the warehouse",
                jdbc.queryForObject("SELECT cancel_reason FROM customer_order WHERE order_code = ?",
                        String.class, orderCode));
    }

    @Test
    @WithMockUser(authorities = "ADMIN_GST")
    @DisplayName("authorization is checked before the transition rules, so refusals leak nothing")
    void authorizationPrecedesValidation() {
        // An authorised caller gets a business error for a bad transition…
        assertThrows(com.app.master.service.core.exception.VeloriaException.class,
                () -> salesOrderService.updateOrderStatus(orderCode, "DELIVERED"));
    }

    @Test
    @WithMockUser(authorities = "VIEW_GST")
    @DisplayName("…while an unauthorised caller is refused without learning whether the order exists")
    void unauthorisedLearnsNothingAboutTheOrder() {
        AccessDeniedException denied = assertThrows(AccessDeniedException.class,
                () -> salesOrderService.updateOrderStatus("VO-DOES-NOT-EXIST-AT-ALL", "PACKED"));
        assertFalse(denied.getMessage().toLowerCase().contains("not found"),
                "a denial must not double as an existence oracle: " + denied.getMessage());
    }
}
