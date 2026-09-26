package com.app.master.service.validation;

import com.app.master.service.core.request.client.PlaceOrderRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Server-side rejection of malformed orders.
 *
 * These annotations were already on {@link PlaceOrderRequest}; they did nothing
 * because no Bean Validation implementation was on the classpath, so
 * {@code @Valid} was a no-op and an order for zero units was accepted. The
 * first test therefore asserts the validator exists at all — if the dependency
 * is removed again, it fails rather than the suite quietly going green.
 *
 * The browser is not a validator: every case here is asserted against the
 * request object itself, independent of any frontend.
 */
class PlaceOrderRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private static PlaceOrderRequest order(String deliveryLocation, Integer quantity, UUID productUuid) {
        PlaceOrderRequest r = new PlaceOrderRequest();
        r.setDeliveryLocation(deliveryLocation);
        PlaceOrderRequest.OrderItemRequest item = new PlaceOrderRequest.OrderItemRequest();
        item.setProductUuid(productUuid);
        item.setQuantity(quantity);
        r.setItems(List.of(item));
        return r;
    }

    private static Set<ConstraintViolation<PlaceOrderRequest>> violations(PlaceOrderRequest r) {
        return validator.validate(r);
    }

    private static String paths(Set<? extends ConstraintViolation<?>> v) {
        return v.stream().map(x -> x.getPropertyPath().toString()).collect(Collectors.joining(", "));
    }

    @Test
    @DisplayName("A Bean Validation implementation is actually on the classpath")
    void validatorExists() {
        assertNotNull(validator, "no validator — @Valid would be inert and every case below meaningless");
    }

    @Test
    @DisplayName("A well-formed order passes")
    void validOrderPasses() {
        assertTrue(violations(order("Bengaluru, Karnataka 560001", 1, UUID.randomUUID())).isEmpty());
    }

    @Test
    @DisplayName("Quantity 0 is rejected")
    void zeroQuantityRejected() {
        Set<ConstraintViolation<PlaceOrderRequest>> v =
                violations(order("Bengaluru, Karnataka 560001", 0, UUID.randomUUID()));
        assertFalse(v.isEmpty(), "an order for zero units must not be accepted");
        assertTrue(paths(v).contains("quantity"), "the offending field should be named, was: " + paths(v));
    }

    @Test
    @DisplayName("Negative quantity is rejected")
    void negativeQuantityRejected() {
        assertFalse(violations(order("Bengaluru, Karnataka 560001", -1, UUID.randomUUID())).isEmpty());
    }

    @Test
    @DisplayName("A blank delivery location is rejected")
    void blankDeliveryLocationRejected() {
        Set<ConstraintViolation<PlaceOrderRequest>> v = violations(order("   ", 1, UUID.randomUUID()));
        assertFalse(v.isEmpty());
        assertTrue(paths(v).contains("deliveryLocation"));
    }

    @Test
    @DisplayName("A missing delivery location is rejected")
    void nullDeliveryLocationRejected() {
        assertFalse(violations(order(null, 1, UUID.randomUUID())).isEmpty());
    }

    @Test
    @DisplayName("An order with no items is rejected")
    void emptyItemsRejected() {
        PlaceOrderRequest r = new PlaceOrderRequest();
        r.setDeliveryLocation("Bengaluru, Karnataka 560001");
        r.setItems(List.of());
        Set<ConstraintViolation<PlaceOrderRequest>> v = violations(r);
        assertFalse(v.isEmpty(), "an order with nothing in it must not be accepted");
        assertTrue(paths(v).contains("items"));
    }

    @Test
    @DisplayName("Item violations are reported against the item, so the caller can find them")
    void itemViolationNamesTheItem() {
        Set<ConstraintViolation<PlaceOrderRequest>> v =
                violations(order("Bengaluru, Karnataka 560001", 0, UUID.randomUUID()));
        assertTrue(paths(v).contains("items[0]"), "expected an indexed path, was: " + paths(v));
    }

    @Test
    @DisplayName("The quantity message says what is required, without echoing the value")
    void messageIsUsefulAndDoesNotEchoInput() {
        ConstraintViolation<PlaceOrderRequest> v =
                violations(order("Bengaluru, Karnataka 560001", 0, UUID.randomUUID()))
                        .stream().filter(x -> x.getPropertyPath().toString().contains("quantity"))
                        .findFirst().orElseThrow();
        assertEquals("quantity must be at least 1", v.getMessage());
    }
}
