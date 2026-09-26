package com.app.master.service.service.client;

import com.app.master.service.core.entity.CustomerOrderEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.PlaceOrderRequest;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.client.PlaceOrderResponse;
import com.app.master.service.repository.admin.CustomerOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Makes checkout safe to retry.
 *
 * <p>Inventory is consumed by order creation, so a duplicate order is not merely
 * an untidy record — it is a second deduction of stock. A customer whose
 * response was lost to a timeout, or who double-clicked, must end up with the
 * one order the first attempt created.
 *
 * <p><b>The database is the authority.</b> The protection is the partial unique
 * index {@code ux_customer_order_client_reference}, not the lookup below. The
 * lookup is a fast path for ordinary retries; two genuinely simultaneous
 * requests will both pass it, and the loser is then rejected by the index. That
 * is why no {@code synchronized} block, static map or cache appears here: those
 * protect one JVM, and this application may run behind a load balancer on
 * several.
 *
 * <p>This class deliberately sits <em>outside</em> the order transaction. A
 * failed attempt must be fully rolled back before the winning order can be
 * read, and that rollback happens as {@link ClientOrderService#placeOrder}
 * returns control here. The same rollback releases the inventory the loser had
 * claimed, so one idempotent checkout leaves exactly one order and exactly one
 * deduction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClientOrderIdempotencyService {

    private final ClientOrderService clientOrderService;
    private final CustomerOrderRepository orderRepository;
    private final ClientSessionStore sessionStore;

    /**
     * Places the order, or returns the one this reference already produced.
     *
     * A request with no reference behaves exactly as before — unprotected, but
     * unchanged — so clients that have not been updated keep working.
     */
    public PlaceOrderResponse placeOrder(String token, PlaceOrderRequest request) throws VeloriaException {
        String reference = trimmedToNull(request.getClientOrderReference());
        if (reference == null) {
            return clientOrderService.placeOrder(token, request);
        }

        String customerId = resolveCustomerId(token);
        String fingerprint = fingerprint(request);

        // Fast path: an ordinary retry, where the first attempt has committed.
        Optional<CustomerOrderEntity> already = orderRepository.findByClientOrderReference(reference);
        if (already.isPresent()) {
            return replay(already.get(), reference, customerId, fingerprint);
        }

        try {
            return clientOrderService.placeOrder(token, request);
        } catch (VeloriaException | RuntimeException failure) {
            // The attempt failed and its transaction has rolled back, so nothing
            // it claimed survives. If the reference has materialised in the
            // meantime, a simultaneous request for this same checkout won, and
            // this caller must be given that order rather than an error.
            //
            // The failure is frequently the winner's own side effect, and not
            // always the unique index: threads serialise on the inventory row
            // lock first, so the loser typically reaches the stock check after
            // the winner has consumed the units and is refused there. Re-reading
            // the reference covers every shape this takes.
            Optional<CustomerOrderEntity> winner = orderRepository.findByClientOrderReference(reference);
            if (winner.isEmpty()) {
                throw failure;       // a genuine failure: out of stock, bad address, …
            }
            log.info("Checkout {} failed with \"{}\" but the reference already holds order {}; returning it",
                    reference, failure.getMessage(), winner.get().getOrderCode());
            return replay(winner.get(), reference, customerId, fingerprint);
        }
    }

    // ── replay and conflict ──────────────────────────────────────────────────

    private PlaceOrderResponse replay(CustomerOrderEntity existing, String reference,
                                      String customerId, String fingerprint) throws VeloriaException {

        // Someone else's reference. Refused, and nothing about their order is
        // disclosed — not the code, not the owner, not that it even exists.
        if (!java.util.Objects.equals(existing.getCustomerId(), customerId)) {
            log.warn("Checkout reference {} replayed by customer {} but belongs to another customer",
                    reference, customerId);
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "This checkout reference cannot be used. Please start a new checkout.");
        }

        // Same reference, different cart. Returning the first order would be
        // wrong — the customer asked for something else — and changing the first
        // order would silently rewrite a committed one.
        if (existing.getRequestFingerprint() != null
                && !existing.getRequestFingerprint().equals(fingerprint)) {
            log.warn("Checkout reference {} replayed with a different request by customer {}",
                    reference, customerId);
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "This checkout reference was already used for a different order. "
                    + "Please start a new checkout.");
        }

        log.info("Checkout reference {} already produced order {}; returning it unchanged",
                reference, existing.getOrderCode());
        return PlaceOrderResponse.builder()
                .orderUuid(existing.getUuid())
                .orderCode(existing.getOrderCode())
                .build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Who is checking out, as {@code customer_order.customer_id} stores it.
     *
     * The session already carries this value, and it is the same user uuid that
     * {@link ClientOrderService#placeOrder} writes onto the order, so the
     * ownership comparison in {@link #replay} is like for like.
     */
    private String resolveCustomerId(String token) throws VeloriaException {
        ClientSessionStore.SessionData session = sessionStore.get(token);
        if (session == null) {
            throw new VeloriaException(ResponseCode.UNAUTHORIZED, "Session expired. Please sign in again.");
        }
        return session.userId();
    }

    /**
     * A stable digest of what was actually ordered.
     *
     * Lines are sorted, so the same cart submitted in a different order is still
     * the same request. Nothing identifying goes in: the reference is opaque and
     * the digest is one-way, so neither carries customer data.
     *
     * <p>Static, and the only definition, because the order-placing service
     * stores what this produces and this service compares against it — two
     * implementations that drifted apart would turn every retry into a conflict.
     */
    public static String fingerprint(PlaceOrderRequest request) {
        List<PlaceOrderRequest.OrderItemRequest> items =
                request.getItems() == null ? List.of() : request.getItems();

        String canonical = String.join("|",
                nullSafe(request.getDeliveryLocation()),
                nullSafe(request.getCurrency()),
                items.stream()
                        .map(i -> nullSafe(String.valueOf(i.getProductUuid()))
                                + ":" + (i.getQuantity() == null ? "1" : i.getQuantity())
                                + ":" + nullSafe(i.getSize())
                                + ":" + nullSafe(i.getSelectedDimension()))
                        .sorted(Comparator.naturalOrder())
                        .collect(Collectors.joining(",")));

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String nullSafe(String s) { return s == null ? "" : s.trim(); }

    /**
     * A blank reference is no reference.
     *
     * Shared with the order-placing service so both agree on what counts as
     * absent — otherwise one would protect a request the other stored as "".
     */
    public static String trimmedToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
