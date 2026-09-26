package com.app.master.service.security;

import com.app.master.service.core.entity.CustomerOrderEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.CustomerOrderItemRepository;
import com.app.master.service.repository.admin.CustomerOrderRepository;
import com.app.master.service.repository.admin.InventoryProductRepository;
import com.app.master.service.service.client.ClientSessionStore;
import com.app.master.service.service.client.impl.ClientOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * An order code identifies an order; it does not authorise reading one.
 *
 * These tests pin the ownership check at the service boundary, so it holds no
 * matter which controller reaches the service.
 */
class OrderOwnershipTest {

    private static final String OWNER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ID = "22222222-2222-2222-2222-222222222222";
    private static final String ORDER_CODE = "VO-20260923-ABCD1234";

    private ClientSessionStore sessionStore;
    private CustomerOrderRepository orderRepository;
    private CustomerOrderItemRepository orderItemRepository;
    private ClientOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        sessionStore = mock(ClientSessionStore.class);
        orderRepository = mock(CustomerOrderRepository.class);
        orderItemRepository = mock(CustomerOrderItemRepository.class);

        // The constructor is wide and most of its collaborators are irrelevant
        // here, so the instance is created without it and only the fields this
        // code path touches are injected.
        service = mock(ClientOrderServiceImpl.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        ReflectionTestUtils.setField(service, "sessionStore", sessionStore);
        ReflectionTestUtils.setField(service, "orderRepository", orderRepository);
        ReflectionTestUtils.setField(service, "orderItemRepository", orderItemRepository);
        ReflectionTestUtils.setField(service, "productRepository", mock(InventoryProductRepository.class));

        CustomerOrderEntity order = CustomerOrderEntity.builder()
                .id(1L).orderCode(ORDER_CODE).customerId(OWNER_ID)
                .customerName("Owner").customerEmail("owner@example.test")
                .deliveryLocation("Bengaluru, Karnataka 560001")
                .status("ORDER_PLACED").orderPlacedAt(Instant.now())
                .totalValue(150000L).build();
        when(orderRepository.findByOrderCodeAndArchiveFalse(ORDER_CODE)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L)).thenReturn(List.of());
    }

    private void sessionFor(String userId) {
        when(sessionStore.get(anyString())).thenReturn(ClientSessionStore.SessionData.builder()
                .userId(userId).name("N").email("n@example.test").phone("0")
                .expiry(Instant.now().plusSeconds(3600)).build());
    }

    @Test
    @DisplayName("The buyer who placed the order passes the ownership gate")
    void ownerCanRead() {
        sessionFor(OWNER_ID);
        // Assembling the whole response needs collaborators unrelated to
        // authorization, so the gate itself is what is asserted: for the owner
        // the method proceeds to load the order's items, and it never throws
        // the refusal the other buyer gets.
        try {
            service.getOrderConfirmation("token-owner", ORDER_CODE);
        } catch (VeloriaException e) {
            fail("owner must not be refused, got: " + e.getErrorCode() + " " + e.getMessage());
        } catch (RuntimeException ignored) {
            // response assembly, not authorization
        }
        verify(orderItemRepository).findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(1L);
    }

    @Test
    @DisplayName("Another signed-in buyer cannot read it, and is told only that it was not found")
    void otherCustomerIsRefused() {
        sessionFor(OTHER_ID);
        VeloriaException e = assertThrows(VeloriaException.class,
                () -> service.getOrderConfirmation("token-other", ORDER_CODE));

        assertEquals(ResponseCode.NOT_FOUND, e.getErrorCode(),
                "must be indistinguishable from an order that does not exist");
        assertFalse(e.getMessage().toLowerCase().contains("owner"), "must not leak the owner");
        assertFalse(e.getMessage().contains(OWNER_ID), "must not leak the owner id");
        assertFalse(e.getMessage().toLowerCase().contains("forbidden"),
                "must not confirm that the order code is real");
    }

    @Test
    @DisplayName("A caller with no valid session is refused")
    void unauthenticatedIsRefused() {
        when(sessionStore.get(anyString())).thenReturn(null);
        VeloriaException e = assertThrows(VeloriaException.class,
                () -> service.getOrderConfirmation("stale", ORDER_CODE));
        assertEquals(ResponseCode.UNAUTHORIZED, e.getErrorCode());
    }

    @Test
    @DisplayName("A refused read loads no order items — nothing of the order is assembled")
    void refusedReadTouchesNoOrderData() {
        sessionFor(OTHER_ID);
        assertThrows(VeloriaException.class, () -> service.getOrderConfirmation("token-other", ORDER_CODE));
        verify(orderItemRepository, never()).findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(any());
    }

    @Test
    @DisplayName("An order that does not exist gives the same answer as one owned by someone else")
    void unknownOrderIsIndistinguishable() {
        sessionFor(OTHER_ID);
        when(orderRepository.findByOrderCodeAndArchiveFalse("VO-NOPE")).thenReturn(Optional.empty());

        VeloriaException missing = assertThrows(VeloriaException.class,
                () -> service.getOrderConfirmation("t", "VO-NOPE"));
        VeloriaException foreign = assertThrows(VeloriaException.class,
                () -> service.getOrderConfirmation("t", ORDER_CODE));

        assertEquals(missing.getErrorCode(), foreign.getErrorCode());
        assertEquals(missing.getMessage(), foreign.getMessage(),
                "the endpoint must not become an order-code oracle");
    }
}
