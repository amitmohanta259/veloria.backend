package com.app.master.service.service.admin.impl;

import com.app.master.service.core.dto.SalesOrderRequest;
import com.app.master.service.core.entity.CustomerOrderEntity;
import com.app.master.service.core.entity.InventoryProductEntity;
import com.app.master.service.core.entity.SalesOrderEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.order.OrderStatus;
import com.app.master.service.core.payment.CancellationActor;
import com.app.master.service.core.payment.CancellationReason;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.admin.SalesOrderResponse;
import com.app.master.service.core.response.admin.SalesStatsResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.repository.admin.CustomerOrderItemRepository;
import com.app.master.service.repository.admin.CustomerOrderRepository;
import com.app.master.service.repository.admin.InventoryProductRepository;
import com.app.master.service.repository.admin.SalesOrderRepository;
import com.app.master.service.service.admin.AccountingPostingService;
import com.app.master.service.service.admin.SalesOrderService;
import com.google.common.base.Strings;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.UUID;

@Service
public class SalesOrderServiceImpl extends AppService implements SalesOrderService {

    private final SalesOrderRepository repository;
    private final InventoryProductRepository productRepository;
    private final CustomerOrderItemRepository customerOrderItemRepository;
    private final CustomerOrderRepository customerOrderRepository;
    /** Cancelling an order has to take its sale back out of the books. */
    private final AccountingPostingService accountingPostingService;
    /**
     * Settles cash on delivery when an order reaches the customer.
     *
     * <p>Injected lazily: the payment service needs this one for cancellation on
     * payment failure, and this one needs the payment service for COD, so the
     * two reference each other. The alternative — a third class that does
     * nothing but forward calls — would obscure where the behaviour lives.
     */
    private final com.app.master.service.service.payment.PaymentService paymentService;

    public SalesOrderServiceImpl(SalesOrderRepository repository,
                                  InventoryProductRepository productRepository,
                                  CustomerOrderItemRepository customerOrderItemRepository,
                                  CustomerOrderRepository customerOrderRepository,
                                  AccountingPostingService accountingPostingService,
                                  @org.springframework.context.annotation.Lazy
                                  com.app.master.service.service.payment.PaymentService paymentService) {
        this.repository = repository;
        this.productRepository = productRepository;
        this.customerOrderItemRepository = customerOrderItemRepository;
        this.customerOrderRepository = customerOrderRepository;
        this.accountingPostingService = accountingPostingService;
        this.paymentService = paymentService;
    }

    @Override
    @Transactional
    public UUID createSalesOrder(SalesOrderRequest request) throws VeloriaException {
        InventoryProductEntity product = productRepository.findByUuid(request.getProductUuid())
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Product not found"));

        long count = repository.countByArchiveFalse();
        String code = String.format("SAL-%d-%04d", Year.now().getValue(), count + 1);

        int qty = request.getQuantity() != null && request.getQuantity() > 0 ? request.getQuantity() : 1;
        long unitPrice = product.getSellingPrice() != null ? product.getSellingPrice() : (product.getPrice() != null ? product.getPrice() : 0L);
        long total = unitPrice * qty;
        String currency = !Strings.isNullOrEmpty(request.getCurrency())
                ? request.getCurrency()
                : product.getPriceCurrency() != null ? product.getPriceCurrency().name() : "INR";

        SalesOrderEntity entity = SalesOrderEntity.builder()
                .orderCode(code)
                .productUuid(product.getUuid())
                .productName(product.getName())
                .skuId(product.getSkuId())
                .quantity(qty)
                .unitPrice(unitPrice)
                .totalValue(total)
                .currency(currency)
                .customerName(request.getCustomerName())
                .customerEmail(request.getCustomerEmail())
                .status("ORDER_PLACED")
                .returnWindowDays(30)
                .orderPlacedAt(Instant.now())
                .build();

        return repository.save(entity).getUuid();
    }

    @Override
    public SalesOrderResponse byUuid(UUID uuid) throws VeloriaException {
        SalesOrderEntity entity = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Sales order not found"));
        return toResponse(entity);
    }

    @Override
    public Page<SalesOrderResponse> allOrders(String status, Integer month, Integer year,
                                               String search, int page, int pageSize) throws VeloriaException {
        Pageable pageable = PageRequest.of(page, pageSize);
        String statusParam = (Strings.isNullOrEmpty(status) || "All".equalsIgnoreCase(status)) ? null : status;
        String searchParam = Strings.isNullOrEmpty(search) ? null : search.toLowerCase();
        return customerOrderItemRepository.findSalesView(statusParam, month, year, searchParam, pageable)
                .map(this::rowToResponse);
    }

    @Override
    public SalesStatsResponse getStats() throws VeloriaException {
        long orderPlaced = customerOrderRepository.countByStatusAndArchiveFalse("ORDER_PLACED");
        long inTransit   = customerOrderRepository.countByStatusAndArchiveFalse("IN_TRANSIT");
        long done        = customerOrderRepository.countByStatusAndArchiveFalse("DELIVERED");
        Long revenue     = customerOrderRepository.sumAllTotalValue();
        return SalesStatsResponse.builder()
                .orderPlaced(orderPlaced)
                .inTransit(inTransit)
                .done(done)
                .totalRevenue(revenue != null ? revenue : 0L)
                .build();
    }

    /**
     * Moves an order along its lifecycle, refusing anything the business does not do.
     *
     * <p>Authorization is asserted here rather than only on the controller so a
     * future caller — a scheduled job, another service — cannot reach the
     * transition without the same check.
     */
    @Override
    @Transactional
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ADMIN_GST)")
    public void updateOrderStatus(String orderCode, String newStatus) throws VeloriaException {
        OrderStatus target = OrderStatus.of(newStatus)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Unknown order status: " + newStatus));

        OrderStatus current = lockAndRead(orderCode);

        // Asking for the status it already holds is not an error. A retried
        // request, or two operators pressing the same button, should leave one
        // order in one state rather than failing the second caller.
        if (current == target) {
            logger.info("Order {} is already {}; nothing to do", orderCode, target);
            return;
        }

        if (!current.canMoveTo(target)) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "An order that is " + current + " cannot be moved to " + target
                    + (current.isTerminal()
                        ? ". " + current + " is final."
                        : ". Allowed: " + OrderStatus.namesOf(current.allowedNext()) + "."));
        }

        applyStatus(orderCode, target, null);
    }

    /**
     * Cancels an order, and takes its sale back out of the books if one was posted.
     *
     * <p>Kept separate from {@link #updateOrderStatus} because it also records a
     * reason, but it is judged by the same transition table: an order that has
     * shipped cannot be cancelled by calling this instead.
     *
     * <p>The status change and the accounting reversal share one transaction, so
     * the books cannot end up disagreeing with the order. In particular, if the
     * reversal cannot be posted because the accounting period is closed, the
     * cancellation fails rather than leaving revenue standing for an order that
     * no longer exists — see the period-lock note in the P0-5B report.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ADMIN_GST)")
    public void cancelOrder(String orderCode, String reason) throws VeloriaException {
        cancelAs(orderCode, CancellationActor.ADMIN,
                 CancellationReason.of(reason).orElse(CancellationReason.ADMIN_REQUEST), reason, null);
    }

    /**
     * Cancels an order on behalf of a named actor, enforcing what that actor is
     * allowed to cancel.
     *
     * <p>Four different callers reach a cancellation — an administrator, the
     * customer, the delivery partner at the door, and the application itself
     * when a payment fails — and they are permitted different things. Putting
     * the matrix here means every route is judged by the same table rather than
     * each entry point deciding for itself.
     *
     * <p>Authorization is layered, not replaced. {@link #cancelOrder} keeps its
     * {@code ADMIN_GST} check; the customer route checks session ownership
     * before it arrives; and {@link CancellationActor#mayCancelFrom} is the
     * final say for all of them. A caller cannot widen its own permissions by
     * choosing a different actor, because the actor determines what is allowed
     * rather than what is requested.
     *
     * @param actingCustomerId required when the actor is the customer, so the
     *                         order is verified to be theirs
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelAs(String orderCode, CancellationActor actor, CancellationReason reason,
                         String rawReason, String actingCustomerId) throws VeloriaException {

        if (actor == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "A cancellation must record who made it");
        }
        if (actor.requiresReason() && reason == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A " + actor + " cancellation needs a reason. One of: " + CancellationReason.all());
        }

        OrderStatus current = lockAndRead(orderCode);

        if (current == OrderStatus.CANCELLED) {
            logger.info("Order {} is already cancelled; leaving the original reason in place", orderCode);
            return;   // a second cancellation must not overwrite why it was cancelled,
                      // and must not post a second reversal
        }

        // The customer may only cancel their own order, and only from the
        // statuses the matrix allows them.
        if (actor == CancellationActor.CUSTOMER) {
            CustomerOrderEntity owned = customerOrderRepository.findByOrderCodeAndArchiveFalse(orderCode)
                    .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Order not found"));
            if (actingCustomerId == null || !actingCustomerId.equals(owned.getCustomerId())) {
                logger.warn("Order {} cancellation refused: requested by a customer who does not own it", orderCode);
                throw new VeloriaException(ResponseCode.NOT_FOUND, "Order not found");
            }
        }

        if (!actor.mayCancelFrom(current)) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    describeRefusal(actor, current));
        }

        // The order machine has the final word on whether the move itself is
        // legal, so a widened actor permission can never produce a transition
        // the lifecycle does not allow.
        if (!current.canMoveTo(OrderStatus.CANCELLED)) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "An order that is " + current + " cannot be cancelled."
                    + (current.isTerminal() ? " " + current + " is final." : ""));
        }

        // Keep what the operator actually wrote. The controlled reason is what
        // the matrix is validated against; it does not get to overwrite a
        // human explanation with a category name.
        String recorded = (rawReason != null && !rawReason.isBlank())
                ? rawReason
                : (reason != null ? reason.name() : null);
        applyStatus(orderCode, OrderStatus.CANCELLED, recorded, actor);
    }

    /** Why this actor cannot cancel from here, in terms the caller can act on. */
    private String describeRefusal(CancellationActor actor, OrderStatus current) {
        if (current == OrderStatus.DELIVERED) {
            return "A delivered order cannot be cancelled. Please use the return process instead.";
        }
        return switch (actor) {
            case CUSTOMER -> "This order is already out for delivery and can no longer be cancelled online. "
                           + "Please refuse the delivery or use the return process.";
            case DELIVERY_PARTNER -> "A delivery partner can only cancel an order that is out for delivery.";
            case SYSTEM -> "An order that is " + current + " cannot be cancelled automatically.";
            case ADMIN -> "An order that is " + current + " cannot be cancelled.";
        };
    }

    // ── shared transition machinery ──────────────────────────────────────────

    /**
     * Takes the row lock and returns the status as it stands.
     *
     * <p>Everything after this point in the transaction is judging a status no
     * other transaction can change underneath it.
     */
    private OrderStatus lockAndRead(String orderCode) throws VeloriaException {
        String raw = customerOrderRepository.lockForStatusChange(orderCode)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Order not found: " + orderCode));
        return OrderStatus.of(raw)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Order " + orderCode + " holds a status this application does not recognise: " + raw));
    }

    private void applyStatus(String orderCode, OrderStatus target, String cancelReason) throws VeloriaException {
        applyStatus(orderCode, target, cancelReason, null);
    }

    private void applyStatus(String orderCode, OrderStatus target, String cancelReason,
                             CancellationActor actor) throws VeloriaException {
        CustomerOrderEntity order = customerOrderRepository.findByOrderCodeAndArchiveFalse(orderCode)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Order not found: " + orderCode));

        order.setStatus(target.name());
        if (cancelReason != null) order.setCancelReason(cancelReason);
        if (target == OrderStatus.CANCELLED) {
            // Who and when, alongside the why P0-5A already recorded.
            order.setCancelledBy(actor != null ? actor.name() : CancellationActor.ADMIN.name());
            order.setCancelledAt(Instant.now());
        }
        if (target == OrderStatus.DELIVERED && order.getDeliveredAt() == null) {
            order.setDeliveredAt(Instant.now());
        }
        customerOrderRepository.save(order);

        // Mirror onto this order's sales_order lines. Ordered by id so two
        // concurrent transitions on different orders take these row locks in the
        // same sequence and cannot deadlock against each other.
        repository.findAll().stream()
                .filter(s -> s.getOrderCode() != null && s.getOrderCode().startsWith(orderCode)
                        && !Boolean.TRUE.equals(s.getArchive()))
                .sorted(java.util.Comparator.comparing(SalesOrderEntity::getId))
                .forEach(s -> { s.setStatus(target.name()); repository.save(s); });

        // Delivery is the approved moment cash on delivery counts as collected.
        // Placed here so it happens however the order reaches DELIVERED, and it
        // is idempotent, so a repeated update settles the receivable once.
        if (target == OrderStatus.DELIVERED) {
            try {
                paymentService.recordCodCollection(order.getId(), orderCode);
            } catch (Exception e) {
                // Delivery is an operational fact; it must not be blocked by an
                // accounting problem. The gap stays visible as an uncollected
                // COD payment rather than an undelivered order.
                logger.error("COD collection could not be recorded for {}: {}", orderCode, e.getMessage());
            }
        }

        // Cancellation reaches this method from two directions — cancelOrder and
        // an ordinary status update to CANCELLED — so the accounting consequence
        // lives here, where both pass through, rather than in one of the callers.
        if (target == OrderStatus.CANCELLED) {
            accountingPostingService.reverseSaleOfCancelledOrder(order,
                    "Order " + orderCode + " cancelled"
                    + (cancelReason == null || cancelReason.isBlank() ? "" : ": " + cancelReason));
        }

        logger.info("Order {} moved to {}", orderCode, target);
    }

    @Override
    public List<SalesOrderResponse> reportAll() throws VeloriaException {
        return customerOrderItemRepository.findSalesView(null, null, null, null, Pageable.unpaged())
                .map(this::rowToResponse)
                .toList();
    }

    private SalesOrderResponse rowToResponse(Object[] row) {
        UUID itemUuid     = (UUID) row[0];
        String orderCode  = (String) row[1];
        UUID productUuid  = (UUID) row[2];
        String productName = (String) row[3];
        String skuId      = (String) row[4];
        Long unitPrice    = row[5] instanceof Long l ? l : ((Number) row[5]).longValue();
        String currency   = (String) row[6];
        String customerName       = (String) row[7];
        String customerEmail      = (String) row[8];
        String status             = (String) row[9];
        Instant placedAt          = row[10] instanceof Timestamp ts ? ts.toInstant() : (Instant) row[10];
        String selectedDimension  = row.length > 11 ? (String) row[11] : null;

        return SalesOrderResponse.builder()
                .uuid(itemUuid)
                .orderCode(orderCode)
                .productUuid(productUuid)
                .productName(productName)
                .skuId(skuId)
                .quantity(1)
                .unitPrice(unitPrice)
                .totalValue(unitPrice)
                .currency(currency)
                .customerName(customerName)
                .customerEmail(customerEmail)
                .status(status)
                .selectedDimension(selectedDimension)
                .returnWindowDays(30)
                .orderPlacedAt(placedAt)
                .build();
    }

    private SalesOrderResponse toResponse(SalesOrderEntity e) {
        return SalesOrderResponse.builder()
                .uuid(e.getUuid())
                .orderCode(e.getOrderCode())
                .productUuid(e.getProductUuid())
                .productName(e.getProductName())
                .skuId(e.getSkuId())
                .quantity(e.getQuantity())
                .unitPrice(e.getUnitPrice())
                .totalValue(e.getTotalValue())
                .currency(e.getCurrency())
                .customerName(e.getCustomerName())
                .customerEmail(e.getCustomerEmail())
                .status(e.getStatus())
                .returnWindowDays(e.getReturnWindowDays())
                .orderPlacedAt(e.getOrderPlacedAt())
                .build();
    }
}
