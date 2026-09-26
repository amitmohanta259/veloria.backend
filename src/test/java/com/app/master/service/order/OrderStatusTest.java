package com.app.master.service.order;

import com.app.master.service.core.order.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static com.app.master.service.core.order.OrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The lifecycle rules themselves, without a database.
 *
 * <p>The one that matters most is {@link #sqlConstantMatchesTheEnum()}: the
 * stock queries cannot call a Java method, so the consuming set is written out
 * as a SQL literal. That literal is only trustworthy if something fails when it
 * drifts from the enum it is supposed to mirror.
 */
class OrderStatusTest {

    @Test
    @DisplayName("the SQL consuming list is exactly the set of stock-consuming statuses")
    void sqlConstantMatchesTheEnum() {
        Set<String> fromSql = Arrays.stream(OrderStatus.CONSUMING_SQL.split(","))
                .map(s -> s.trim().replace("'", ""))
                .collect(Collectors.toSet());

        Set<String> fromEnum = Arrays.stream(values())
                .filter(OrderStatus::consumesStock)
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertEquals(fromEnum, fromSql,
                "every stock query uses CONSUMING_SQL; if it disagrees with consumesStock() "
                + "the inventory numbers silently stop matching the lifecycle");
    }

    @Test
    @DisplayName("every status is classified, and only the two that never shipped release stock")
    void everyStatusIsClassified() {
        Set<OrderStatus> releasing = Arrays.stream(values())
                .filter(s -> !s.consumesStock())
                .collect(Collectors.toSet());

        assertEquals(Set.of(CANCELLED, PAYMENT_FAILED), releasing,
                "stock returns to the shelf only when the goods never left");
    }

    @Test
    @DisplayName("the whole return leg still holds its stock")
    void theReturnLegHoldsStock() {
        // A customer asking for a return has not sent anything back yet, and the
        // goods are not sellable while they are in transit to the warehouse.
        for (OrderStatus s : new OrderStatus[]{READY_TO_PICKUP, PICKED_UP, IN_TRANSIT_TO_SELLER, RECEIVED}) {
            assertTrue(s.consumesStock(), s + " must not put the goods back on the shelf");
        }
    }

    @Test
    @DisplayName("out for delivery holds its stock — the P0-4 finding")
    void outForDeliveryHoldsStock() {
        assertTrue(OUT_FOR_DELIVERY.consumesStock(),
                "goods on a van are not available to sell to someone else");
    }

    @Test
    @DisplayName("the forward leg runs placed → packed → in transit → out for delivery → delivered")
    void forwardLeg() {
        assertTrue(ORDER_PLACED.canMoveTo(PACKED));
        assertTrue(PACKED.canMoveTo(IN_TRANSIT));
        assertTrue(IN_TRANSIT.canMoveTo(OUT_FOR_DELIVERY));
        assertTrue(OUT_FOR_DELIVERY.canMoveTo(DELIVERED));
    }

    @Test
    @DisplayName("the forward leg cannot be skipped")
    void forwardLegCannotSkip() {
        assertFalse(ORDER_PLACED.canMoveTo(DELIVERED), "an order cannot arrive before it is packed");
        assertFalse(ORDER_PLACED.canMoveTo(OUT_FOR_DELIVERY));
        assertFalse(PACKED.canMoveTo(DELIVERED));
    }

    @Test
    @DisplayName("cancellation is possible before dispatch and impossible after")
    void cancellationWindow() {
        assertTrue(ORDER_PLACED.canMoveTo(CANCELLED));
        assertTrue(PACKED.canMoveTo(CANCELLED));
        // Widened by the approved P0-7 policy: an order can be stopped after
        // dispatch and at the door. Which actor may do so is decided by
        // CancellationActor, not here — the lifecycle only says the move exists.
        assertTrue(IN_TRANSIT.canMoveTo(CANCELLED), "an order in transit can be stopped");
        assertTrue(OUT_FOR_DELIVERY.canMoveTo(CANCELLED), "and so can one at the door");
        assertFalse(DELIVERED.canMoveTo(CANCELLED), "a delivered order is returned, not cancelled");
    }

    @Test
    @DisplayName("terminal statuses are terminal")
    void terminalStatuses() {
        for (OrderStatus s : new OrderStatus[]{CANCELLED, PAYMENT_FAILED, RETURNED, PARTIALLY_RETURNED}) {
            assertTrue(s.isTerminal(), s + " must be final");
            assertTrue(s.allowedNext().isEmpty());
        }
        // Specifically: a cancelled order cannot be quietly revived.
        assertFalse(CANCELLED.canMoveTo(ORDER_PLACED));
        assertFalse(CANCELLED.canMoveTo(PACKED));
        assertFalse(CANCELLED.canMoveTo(DELIVERED));
    }

    @Test
    @DisplayName("legacy names have no transitions, so nothing can be moved into that limbo")
    void legacyNamesAreInert() {
        assertTrue(DISPATCHED.isLegacy());
        assertTrue(DONE.isLegacy());
        assertTrue(DISPATCHED.allowedNext().isEmpty());
        assertTrue(DONE.allowedNext().isEmpty());
        assertTrue(Arrays.stream(values()).noneMatch(s -> s.canMoveTo(DISPATCHED)),
                "nothing may move an order into an undefined status");
        assertTrue(Arrays.stream(values()).noneMatch(s -> s.canMoveTo(DONE)));
        // They still count as sold so historical rows are not double-counted.
        assertTrue(DISPATCHED.consumesStock());
        assertTrue(DONE.consumesStock());
    }

    @Test
    @DisplayName("unknown strings are not statuses")
    void unknownStrings() {
        assertTrue(OrderStatus.of("SHIPPED_TO_MARS").isEmpty());
        assertTrue(OrderStatus.of("").isEmpty());
        assertTrue(OrderStatus.of(null).isEmpty());
        assertTrue(OrderStatus.of("  delivered ").isPresent(), "whitespace and case are tolerated");
        assertEquals(DELIVERED, OrderStatus.of("delivered").orElseThrow());
    }
}
