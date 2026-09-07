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
import com.app.master.service.service.client.ClientOrderService;
import com.app.master.service.service.client.ClientSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        return PlaceOrderResponse.builder()
                .orderUuid(saved.getUuid())
                .orderCode(orderCode)
                .build();
    }

    @Override
    public OrderConfirmationResponse getOrderConfirmation(String orderCode) throws VeloriaException {
        CustomerOrderEntity order = orderRepository.findByOrderCodeAndArchiveFalse(orderCode)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Order not found"));

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
}
