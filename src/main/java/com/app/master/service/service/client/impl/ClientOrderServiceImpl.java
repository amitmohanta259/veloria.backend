package com.app.master.service.service.client.impl;

import com.app.master.service.core.entity.*;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.PlaceOrderRequest;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.client.OrderConfirmationResponse;
import com.app.master.service.core.response.client.OrderHistoryResponse;
import com.app.master.service.core.response.client.PlaceOrderResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.core.service.AwsService;
import com.app.master.service.repository.admin.*;
import com.app.master.service.repository.client.UserAddressRepository;
import com.app.master.service.repository.client.UserRepository;
import com.app.master.service.repository.admin.GstOutputTaxRepository;
import com.app.master.service.service.admin.GstAuditService;
import com.app.master.service.service.admin.GstCalculationService;
import com.app.master.service.service.admin.GstIdentityService;
import com.app.master.service.service.admin.GstMovementService;
import com.app.master.service.service.admin.PlaceOfSupplyResolver;
import com.app.master.service.service.admin.AccountingPostingService;
import com.app.master.service.service.client.ClientOrderIdempotencyService;
import com.app.master.service.service.client.ClientOrderService;
import com.app.master.service.service.client.ClientSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClientOrderServiceImpl extends AppService implements ClientOrderService {

    private final ClientSessionStore sessionStore;
    private final UserRepository userRepository;
    private final CustomerOrderRepository orderRepository;
    private final CustomerOrderItemRepository orderItemRepository;
    private final InventoryProductRepository productRepository;
    private final InventoryProductImagesRepository imagesRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final BusinessDetailsRepository businessDetailsRepository;
    private final UserAddressRepository userAddressRepository;
    private final AwsService awsService;
    private final GstCalculationService gstCalculationService;
    private final GstOutputTaxRepository gstOutputTaxRepository;
    private final GstMovementService gstMovementService;
    private final GstAuditService gstAuditService;
    private final GstIdentityService gstIdentityService;
    private final PlaceOfSupplyResolver placeOfSupplyResolver;
    /** The approved model recognises a sale when the order is created. */
    private final AccountingPostingService accountingPostingService;

    private static int quantityOf(PlaceOrderRequest.OrderItemRequest r) {
        return r.getQuantity() != null && r.getQuantity() > 0 ? r.getQuantity() : 1;
    }

    @Override
    @Transactional
    public PlaceOrderResponse placeOrder(String token, PlaceOrderRequest request) throws VeloriaException {
        ClientSessionStore.SessionData session = sessionStore.get(token);
        if (session == null) {
            throwError(ResponseCode.UNAUTHORIZED, "Session expired. Please sign in again.");
        }

        UserEntity user = userRepository.findByEmailAndArchiveFalse(session.email())
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "User account not found."));

        String orderCode = generateOrderCode();
        String customerId = user.getUuid().toString();
        // Trimmed to nothing means "not supplied". An empty string would otherwise
        // be a real value that only one order in the whole system could ever hold.
        String clientOrderReference =
                ClientOrderIdempotencyService.trimmedToNull(request.getClientOrderReference());
        String customerName = user.getFirstName() + (user.getLastName() != null ? " " + user.getLastName() : "");
        String currency = request.getCurrency() != null ? request.getCurrency() : "INR";
        Instant now = Instant.now();

        // Place of supply drives CGST+SGST vs IGST. Resolution now falls back
        // through the address book, state name and PIN code rather than
        // silently yielding null (which used to force every order intra-state).
        PlaceOfSupplyResolver.Resolution pos =
                placeOfSupplyResolver.resolve(request.getDeliveryLocation(), customerId);
        String buyerStateCode = pos.stateCode();

        // Seller state is derived from the business GSTIN, which is the
        // authoritative source; business_details.seller_state_code disagreed.
        String sellerStateCode = gstIdentityService.sellerStateCode();
        String sellerGstin = gstIdentityService.businessGstin();

        if (!pos.resolved()) {
            log.warn("Order {}: place of supply unresolved from '{}'. GST will be recorded as PENDING_REVIEW.",
                    orderCode, request.getDeliveryLocation());
        }

        // Resolve products up front so we can compute the order total before saving
        List<PlaceOrderRequest.OrderItemRequest> itemRequests = request.getItems();
        List<Optional<InventoryProductEntity>> resolvedProducts = itemRequests.stream()
                .map(r -> productRepository.findByUuid(r.getProductUuid()))
                .collect(Collectors.toList());

        // Inventory is claimed before anything is written. Placing the order is
        // the deduction (stock is derived from order items), so this both checks
        // availability and holds the serialisation lock until COMMIT.
        reserveInventory(itemRequests, resolvedProducts);

        // Compute per-item GST and accumulate order totals
        long taxableValue = 0L;
        long totalCgst = 0L;
        long totalSgst = 0L;
        long totalIgst = 0L;

        // Place of supply, not a silent fallback to the seller's own state.
        String placeOfSupply = buyerStateCode;
        boolean gstResolvable = pos.resolved() && sellerStateCode != null;

        List<GstCalculationService.GstResult> gstResults = new ArrayList<>();
        boolean anyUnresolved = !gstResolvable;

        for (int i = 0; i < resolvedProducts.size(); i++) {
            Optional<InventoryProductEntity> opt = resolvedProducts.get(i);
            if (opt.isEmpty()) {
                gstResults.add(null);
                continue;
            }
            InventoryProductEntity p = opt.get();
            long unitPrice = p.getSellingPrice() != null ? p.getSellingPrice() : 0L;
            int qty = quantityOf(itemRequests.get(i));

            GstCalculationService.GstResult gst;
            if (gstResolvable) {
                gst = gstCalculationService.calculate(
                        p.getHsnCode(), unitPrice, qty, placeOfSupply, sellerStateCode,
                        now.atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate());
                if (gst.unresolved()) anyUnresolved = true;
            } else {
                // Cannot legally classify the supply; record the sale with zero
                // tax and flag it rather than guessing a tax head.
                gst = GstCalculationService.GstResult.zero(
                        p.getHsnCode(), unitPrice, unitPrice * qty, qty, "NO_PLACE_OF_SUPPLY");
            }

            gstResults.add(gst);
            taxableValue += gst.taxableValuePaise();
            totalCgst += gst.cgstAmount();
            totalSgst += gst.sgstAmount();
            totalIgst += gst.igstAmount();
        }

        long totalTaxAmount = totalCgst + totalSgst + totalIgst;
        long totalValue = taxableValue + totalTaxAmount;
        String gstStatus = anyUnresolved ? "PENDING_REVIEW" : "POSTED";

        CustomerOrderEntity order = CustomerOrderEntity.builder()
                .uuid(UUID.randomUUID())
                .orderCode(orderCode)
                .customerId(customerId)
                // What makes this order findable when the same checkout is retried.
                // Null when the client sent no reference, which the partial unique
                // index permits; the fingerprint only means something alongside one.
                .clientOrderReference(clientOrderReference)
                .requestFingerprint(clientOrderReference == null ? null
                        : ClientOrderIdempotencyService.fingerprint(request))
                .customerName(customerName)
                .customerEmail(user.getEmail())
                .deliveryLocation(request.getDeliveryLocation())
                .currency(currency)
                .totalValue(totalValue)
                .taxableValue(taxableValue)
                .cgstAmount(totalCgst)
                .sgstAmount(totalSgst)
                .igstAmount(totalIgst)
                .totalTaxAmount(totalTaxAmount)
                .buyerStateCode(buyerStateCode)
                .sellerStateCode(sellerStateCode)
                .placeOfSupply(placeOfSupply)
                .sellerGstin(sellerGstin)
                .customerType("B2C")
                .gstStatus(gstStatus)
                .status("ORDER_PLACED")
                .orderPlacedAt(now)
                .active(true)
                .archive(false)
                .build();

        CustomerOrderEntity saved = orderRepository.save(order);

        // Immediately write output-tax record so it appears in GST accounting
        if (totalCgst > 0 || totalIgst > 0) {
            YearMonth ym = YearMonth.from(now.atZone(ZoneId.of("Asia/Kolkata")));
            int yr = ym.getYear();
            String fy = (ym.getMonthValue() >= 4)
                ? yr + "-" + String.format("%02d", (yr + 1) % 100)
                : (yr - 1) + "-" + String.format("%02d", yr % 100);
            String supplyType = totalIgst > 0 ? "INTER_STATE" : "INTRA_STATE";
            try {
                gstOutputTaxRepository.insertIfAbsent(
                    saved.getId(), saved.getUuid(), orderCode, customerName,
                    null, placeOfSupply, supplyType, fy, ym.toString(),
                    now.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate(),
                    taxableValue, BigDecimal.ZERO, totalCgst,
                    BigDecimal.ZERO, totalSgst, BigDecimal.ZERO, totalIgst,
                    totalTaxAmount, totalValue, "NONE"
                );
            } catch (Exception e) {
                // The order stands, but the GST gap is now a reviewable record
                // rather than a log line nobody reads.
                gstAuditService.recordException("OUTPUT_TAX_WRITE", "CUSTOMER_ORDER",
                        saved.getId(), orderCode, e);
                saved.setGstStatus("PENDING_REVIEW");
                orderRepository.save(saved);
            }
        }

        List<CustomerOrderItemEntity> items = new ArrayList<>();
        List<SalesOrderEntity> salesOrders = new ArrayList<>();

        for (int i = 0; i < itemRequests.size(); i++) {
            PlaceOrderRequest.OrderItemRequest itemReq = itemRequests.get(i);
            GstCalculationService.GstResult gst = gstResults.get(i);

            CustomerOrderItemEntity.CustomerOrderItemEntityBuilder itemBuilder = CustomerOrderItemEntity.builder()
                    .uuid(UUID.randomUUID())
                    .customerOrderId(saved.getId())
                    .productUuid(itemReq.getProductUuid())
                    .currency(currency)
                    .selectedDimension(itemReq.getSelectedDimension())
                    .size(itemReq.getSize())
                    .active(true)
                    .archive(false);

            int qty = quantityOf(itemReq);
            itemBuilder.quantity(qty).returnedQuantity(0);

            if (resolvedProducts.get(i).isPresent()) {
                InventoryProductEntity p = resolvedProducts.get(i).get();
                long unitPrice = p.getSellingPrice() != null ? p.getSellingPrice() : 0L;
                itemBuilder
                        .unitPricePaise(unitPrice)
                        .taxableValuePaise(unitPrice * qty)
                        .hsnCode(p.getHsnCode());
                if (gst != null) {
                    itemBuilder
                            .taxableValuePaise(gst.taxableValuePaise())
                            .cgstRateBp(gst.cgstRateBp())
                            .sgstRateBp(gst.sgstRateBp())
                            .igstRateBp(gst.igstRateBp())
                            .cgstAmount(gst.cgstAmount())
                            .sgstAmount(gst.sgstAmount())
                            .igstAmount(gst.igstAmount())
                            .totalTaxPaise(gst.totalTax());
                }
            }

            items.add(itemBuilder.build());

            resolvedProducts.get(i).ifPresent(product -> {
                long unitPrice = product.getSellingPrice() != null ? product.getSellingPrice() : 0L;
                salesOrders.add(SalesOrderEntity.builder()
                        .orderCode(orderCode + "-" + (salesOrders.size() + 1))
                        .productUuid(product.getUuid())
                        .productName(product.getName())
                        .skuId(product.getSkuId())
                        .quantity(qty)
                        .unitPrice(unitPrice)
                        .totalValue(unitPrice * qty)
                        .currency(currency)
                        .customerName(customerName)
                        .customerEmail(user.getEmail())
                        .status("ORDER_PLACED")
                        .returnWindowDays(30)
                        .orderPlacedAt(now)
                        .active(true)
                        .archive(false)
                        .build());
            });
        }

        orderItemRepository.saveAll(items);
        salesOrderRepository.saveAll(salesOrders);
        log.info("Order {} placed by user {}", orderCode, customerId);

        // The order stands even if the ledger write fails, but the failure is
        // recorded as a reviewable accounting exception and the order is
        // flagged — it is never silently swallowed.
        try {
            gstMovementService.recordSaleMovement(saved, items);
        } catch (Exception e) {
            gstAuditService.recordException("SALE_MOVEMENT", "CUSTOMER_ORDER",
                    saved.getId(), orderCode, e);
            saved.setGstStatus("PENDING_REVIEW");
            orderRepository.save(saved);
        }

        postSaleAfterCommit(saved);

        return PlaceOrderResponse.builder()
                .orderUuid(saved.getUuid())
                .orderCode(orderCode)
                .build();
    }

    /**
     * Books the sale once the order is safely committed.
     *
     * <p>Under the approved model a sale is recognised when the order is
     * created, not when it is paid for. It is posted <em>after</em> this
     * transaction commits, for two reasons that both matter:
     *
     * <ul>
     *   <li>the posting opens its own transaction and takes the order's row
     *       lock, which it could not do against a row this transaction has not
     *       yet written;</li>
     *   <li>an accounting failure must never undo a customer's order. If the
     *       order's accounting period is closed the journal cannot be written —
     *       the approved closed-period policy is that the order still stands —
     *       so the failure is logged and the order is left for the accounting
     *       backfill to pick up, rather than the customer being told their
     *       purchase failed.</li>
     * </ul>
     *
     * <p>Posting is idempotent on the order, so the backfill cannot later
     * produce a second sale for one this has already booked.
     */
    private void postSaleAfterCommit(CustomerOrderEntity order) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            postSaleQuietly(order);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                postSaleQuietly(order);
            }
        });
    }

    private void postSaleQuietly(CustomerOrderEntity order) {
        try {
            if (accountingPostingService.postSale(order) != null) {
                log.info("Sale posted for order {}", order.getOrderCode());
            }
        } catch (Exception e) {
            // Deliberately not rethrown: the money side of an order must not be
            // able to cancel the order itself. The gap is visible in the books
            // as an order with no sale, and the backfill will post it once the
            // obstruction — most often a closed period — is cleared.
            log.error("Sale could not be posted for order {}: {}",
                    order.getOrderCode(), e.getMessage());
        }
    }

    @Override
    public OrderConfirmationResponse getOrderConfirmation(String token, String orderCode) throws VeloriaException {
        ClientSessionStore.SessionData session = sessionStore.get(token);
        if (session == null) {
            throwError(ResponseCode.UNAUTHORIZED, "Session expired. Please sign in again.");
        }

        CustomerOrderEntity order = orderRepository.findByOrderCodeAndArchiveFalse(orderCode)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Order not found"));

        // Someone else's order is reported exactly as a non-existent one. A
        // distinct 403 would confirm that the code is real, which is itself a
        // disclosure — it turns the endpoint into an order-code oracle.
        if (!java.util.Objects.equals(order.getCustomerId(), session.userId())) {
            log.warn("Order {} requested by user {} who does not own it", orderCode, session.userId());
            throw new VeloriaException(ResponseCode.NOT_FOUND, "Order not found");
        }

        List<CustomerOrderItemEntity> orderItems =
                orderItemRepository.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(order.getId());

        List<UUID> productUuids = orderItems.stream()
                .map(CustomerOrderItemEntity::getProductUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<InventoryProductEntity> products = productRepository.findByUuidInAndArchiveFalse(productUuids);
        Map<UUID, InventoryProductEntity> productMap = products.stream()
                .collect(Collectors.toMap(InventoryProductEntity::getUuid, p -> p));

        Map<UUID, String> imageMap = new HashMap<>();
        if (!productUuids.isEmpty()) {
            for (Object[] row : imagesRepository.getInventoryProductListImages(productUuids)) {
                UUID uuid = (UUID) row[0];
                String key = (String) row[1];
                imageMap.putIfAbsent(uuid, presign(key));
            }
        }

        List<OrderConfirmationResponse.OrderItemDetail> itemDetails = orderItems.stream()
                .map(item -> {
                    InventoryProductEntity product = productMap.get(item.getProductUuid());
                    if (product == null) return null;
                    return OrderConfirmationResponse.OrderItemDetail.builder()
                            .productName(product.getName())
                            .skuId(product.getSkuId())
                            .size(item.getSize())
                            .selectedDimension(item.getSelectedDimension())
                            .unitPrice(item.getUnitPricePaise() != null ? item.getUnitPricePaise() : product.getSellingPrice())
                            .hsnCode(item.getHsnCode())
                            .cgstRateBp(item.getCgstRateBp())
                            .sgstRateBp(item.getSgstRateBp())
                            .igstRateBp(item.getIgstRateBp())
                            .cgstAmount(item.getCgstAmount())
                            .sgstAmount(item.getSgstAmount())
                            .igstAmount(item.getIgstAmount())
                            .currency(item.getCurrency())
                            .imageUrl(imageMap.get(item.getProductUuid()))
                            .build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        OrderConfirmationResponse.DeliveryAddress deliveryAddress = resolveDeliveryAddress(order.getDeliveryLocation());

        OrderConfirmationResponse.SenderAddress senderAddress = businessDetailsRepository
                .findFirstByArchiveFalseOrderByIdAsc()
                .map(b -> OrderConfirmationResponse.SenderAddress.builder()
                        .companyName(b.getCompanyName())
                        .companyAddress(b.getCompanyAddress())
                        .registeredPhone(b.getRegisteredPhone())
                        .registeredEmail(b.getRegisteredEmail())
                        .build())
                .orElse(null);

        long total = itemDetails.stream()
                .mapToLong(i -> i.getUnitPrice() != null ? i.getUnitPrice() : 0L)
                .sum();

        return OrderConfirmationResponse.builder()
                .orderCode(order.getOrderCode())
                .customerName(order.getCustomerName())
                .customerEmail(order.getCustomerEmail())
                .status(order.getStatus())
                .currency(order.getCurrency())
                .totalValue(order.getTotalValue() != null ? order.getTotalValue() : total)
                .taxableValue(order.getTaxableValue())
                .cgstAmount(order.getCgstAmount())
                .sgstAmount(order.getSgstAmount())
                .igstAmount(order.getIgstAmount())
                .totalTaxAmount(order.getTotalTaxAmount())
                .buyerStateCode(order.getBuyerStateCode())
                .sellerStateCode(order.getSellerStateCode())
                .placeOfSupply(order.getPlaceOfSupply())
                .orderPlacedAt(order.getOrderPlacedAt())
                .items(itemDetails)
                .deliveryAddress(deliveryAddress)
                .senderAddress(senderAddress)
                .build();
    }

    private OrderConfirmationResponse.DeliveryAddress resolveDeliveryAddress(String deliveryLocation) {
        if (deliveryLocation == null || deliveryLocation.isBlank()) return null;
        try {
            UUID addressUuid = UUID.fromString(deliveryLocation);
            return userAddressRepository.findByUuid(addressUuid)
                    .map(a -> OrderConfirmationResponse.DeliveryAddress.builder()
                            .receiverName(a.getReceiverName())
                            .phone(a.getPhone())
                            .address(a.getAddress())
                            .build())
                    .orElse(null);
        } catch (IllegalArgumentException e) {
            return OrderConfirmationResponse.DeliveryAddress.builder()
                    .address(deliveryLocation)
                    .build();
        }
    }

    private String presign(String key) {
        try {
            return awsService.getViewablePreSignedUrl(key);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    @Transactional
    public String recordFailedOrder(String token, PlaceOrderRequest request) throws VeloriaException {
        ClientSessionStore.SessionData session = sessionStore.get(token);
        if (session == null) throwError(ResponseCode.UNAUTHORIZED, "Session expired.");

        UserEntity user = userRepository.findByEmailAndArchiveFalse(session.email())
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "User not found."));

        String orderCode = generateOrderCode();
        String customerId = user.getUuid().toString();
        String customerName = user.getFirstName() + (user.getLastName() != null ? " " + user.getLastName() : "");
        String currency = request.getCurrency() != null ? request.getCurrency() : "INR";
        Instant now = Instant.now();

        List<PlaceOrderRequest.OrderItemRequest> itemRequests = request.getItems();
        List<Optional<InventoryProductEntity>> resolvedProducts = itemRequests.stream()
                .map(r -> productRepository.findByUuid(r.getProductUuid()))
                .collect(Collectors.toList());

        long totalValue = resolvedProducts.stream()
                .filter(Optional::isPresent)
                .mapToLong(p -> p.get().getSellingPrice() != null ? p.get().getSellingPrice() : 0L)
                .sum();

        CustomerOrderEntity order = CustomerOrderEntity.builder()
                .uuid(UUID.randomUUID())
                .orderCode(orderCode)
                .customerId(customerId)
                .customerName(customerName)
                .customerEmail(user.getEmail())
                .deliveryLocation(request.getDeliveryLocation())
                .currency(currency)
                .totalValue(totalValue)
                .status("PAYMENT_FAILED")
                .orderPlacedAt(now)
                .active(true)
                .archive(false)
                .build();

        CustomerOrderEntity saved = orderRepository.save(order);

        List<CustomerOrderItemEntity> items = new ArrayList<>();
        for (PlaceOrderRequest.OrderItemRequest itemReq : itemRequests) {
            items.add(CustomerOrderItemEntity.builder()
                    .uuid(UUID.randomUUID())
                    .customerOrderId(saved.getId())
                    .productUuid(itemReq.getProductUuid())
                    .currency(currency)
                    .selectedDimension(itemReq.getSelectedDimension())
                    .size(itemReq.getSize())
                    .active(true)
                    .archive(false)
                    .build());
        }
        orderItemRepository.saveAll(items);
        return orderCode;
    }

    @Override
    public List<OrderHistoryResponse> getOrderHistory(String token, Integer year) throws VeloriaException {
        ClientSessionStore.SessionData session = sessionStore.get(token);
        if (session == null) {
            throwError(ResponseCode.UNAUTHORIZED, "Session expired. Please sign in again.");
        }

        UserEntity user = userRepository.findByEmailAndArchiveFalse(session.email())
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "User not found."));

        String customerId = user.getUuid().toString();
        List<CustomerOrderEntity> orders = orderRepository.findByCustomerIdAndYear(customerId, year);

        // batch-load all items for these orders
        List<Long> orderIds = orders.stream().map(CustomerOrderEntity::getId).collect(Collectors.toList());
        List<CustomerOrderItemEntity> allItems = orderIds.isEmpty()
                ? List.of()
                : orderItemRepository.findByCustomerOrderIdInAndArchiveFalseOrderByIdAsc(orderIds);

        // group items by orderId
        Map<Long, List<CustomerOrderItemEntity>> itemsByOrder = allItems.stream()
                .collect(Collectors.groupingBy(CustomerOrderItemEntity::getCustomerOrderId));

        // resolve product names
        List<UUID> productUuids = allItems.stream()
                .map(CustomerOrderItemEntity::getProductUuid).filter(Objects::nonNull)
                .distinct().collect(Collectors.toList());
        Map<UUID, InventoryProductEntity> productMap = productUuids.isEmpty() ? Map.of() :
                productRepository.findByUuidInAndArchiveFalse(productUuids).stream()
                        .collect(Collectors.toMap(InventoryProductEntity::getUuid, p -> p));

        return orders.stream().map(order -> {
            List<OrderHistoryResponse.OrderItemSummary> itemSummaries = itemsByOrder
                    .getOrDefault(order.getId(), List.of()).stream()
                    .map(item -> {
                        InventoryProductEntity product = item.getProductUuid() != null
                                ? productMap.get(item.getProductUuid()) : null;
                        return OrderHistoryResponse.OrderItemSummary.builder()
                                .productUuid(item.getProductUuid())
                                .productName(product != null ? product.getName() : "Unknown")
                                .skuId(product != null ? product.getSkuId() : null)
                                .size(item.getSize())
                                .selectedDimension(item.getSelectedDimension())
                                .quantity(1)
                                .unitPrice(product != null ? product.getSellingPrice() : null)
                                .build();
                    }).collect(Collectors.toList());

            return OrderHistoryResponse.builder()
                    .orderCode(order.getOrderCode())
                    .status(order.getStatus())
                    .totalValue(order.getTotalValue())
                    .currency(order.getCurrency())
                    .orderPlacedAt(order.getOrderPlacedAt())
                    .deliveredAt(order.getDeliveredAt())
                    .items(itemSummaries)
                    .build();
        }).collect(Collectors.toList());
    }

    private String generateOrderCode() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return "VO-" + date + "-" + suffix;
    }


    /**
     * Claims stock for every line of the order, or fails the whole order.
     *
     * <p><b>Invariant.</b> For each product, after this returns,
     * {@code initial_stock − sold + returned − requested >= 0}, where sold and
     * returned are sums of {@code customer_order_item.quantity}.
     *
     * <p><b>Why a lock rather than a conditional UPDATE.</b> This application
     * derives available stock from order rows; there is no counter column to
     * decrement with {@code WHERE available >= :qty}. The per-product row is
     * therefore used as the serialisation point: a second checkout for the same
     * product blocks on {@code SELECT … FOR UPDATE} until the first commits, and
     * then — under READ COMMITTED, where each statement takes a fresh snapshot —
     * re-reads availability including the order the first one just wrote.
     *
     * <p><b>Deadlock prevention.</b> Locks are taken in ascending
     * {@code inventory_product.id} order, so two concurrent multi-item orders
     * containing the same products acquire them in the same sequence and cannot
     * hold each other's next lock.
     *
     * <p><b>Grain.</b> Product, not size: checkout sends no size (the request
     * carries {@code productUuid} and {@code quantity} only), so every order item
     * has a null size and a size-grain claim could not be attributed. The size
     * rows remain the restock grain and their sum is the product's stock.
     *
     * <p>Quantities for the same product across several lines are summed first,
     * so an order with two lines of the same product is checked against their
     * total rather than each line separately.
     */
    private void reserveInventory(List<PlaceOrderRequest.OrderItemRequest> itemRequests,
                                  List<Optional<InventoryProductEntity>> resolvedProducts)
            throws VeloriaException {

        // product id -> units requested across every line of this order
        Map<Long, Long> requestedByProduct = new LinkedHashMap<>();
        Map<Long, InventoryProductEntity> productsById = new HashMap<>();
        for (int i = 0; i < resolvedProducts.size(); i++) {
            Optional<InventoryProductEntity> opt = resolvedProducts.get(i);
            if (opt.isEmpty()) continue;   // unresolved lines are handled by the existing loop
            InventoryProductEntity product = opt.get();
            long qty = quantityOf(itemRequests.get(i));
            if (qty <= 0) {
                // Bean Validation rejects this at the edge; belt and braces so a
                // bypassed edge cannot deduct a nonsensical amount.
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Quantity must be at least 1 for " + product.getName());
            }
            requestedByProduct.merge(product.getId(), qty, Long::sum);
            productsById.putIfAbsent(product.getId(), product);
        }

        // Ascending product id: the deterministic order that prevents deadlock.
        List<Long> lockOrder = new ArrayList<>(requestedByProduct.keySet());
        Collections.sort(lockOrder);

        for (Long productId : lockOrder) {
            productRepository.lockForInventoryUpdate(productId);

            long requested = requestedByProduct.get(productId);
            Long availableValue = productRepository.availableStock(productId);
            long available = availableValue == null ? 0L : availableValue;

            if (available < requested) {
                InventoryProductEntity product = productsById.get(productId);
                log.warn("Order rejected: product {} ({}) has {} available, {} requested",
                        product.getUuid(), product.getName(), available, requested);
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Only " + Math.max(available, 0) + " left of " + product.getName()
                                + ". Please reduce the quantity.");
            }
        }
    }
}
