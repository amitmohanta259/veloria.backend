package com.app.master.service.service.client.impl;

import com.app.master.service.core.entity.CustomerOrderEntity;
import com.app.master.service.core.entity.CustomerOrderItemEntity;
import com.app.master.service.core.entity.UserEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.PlaceOrderRequest;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.client.PlaceOrderResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.repository.admin.CustomerOrderItemRepository;
import com.app.master.service.repository.admin.CustomerOrderRepository;
import com.app.master.service.repository.client.UserRepository;
import com.app.master.service.service.client.ClientOrderService;
import com.app.master.service.service.client.ClientSessionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClientOrderServiceImpl extends AppService implements ClientOrderService {

    private final ClientSessionStore sessionStore;
    private final UserRepository userRepository;
    private final CustomerOrderRepository orderRepository;
    private final CustomerOrderItemRepository orderItemRepository;

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

        CustomerOrderEntity order = CustomerOrderEntity.builder()
                .uuid(UUID.randomUUID())
                .orderCode(orderCode)
                .customerId(customerId)
                .customerName(customerName)
                .customerEmail(user.getEmail())
                .deliveryLocation(request.getDeliveryLocation())
                .currency(request.getCurrency() != null ? request.getCurrency() : "INR")
                .totalValue(0L)
                .status("ORDER_PLACED")
                .orderPlacedAt(Instant.now())
                .active(true)
                .archive(false)
                .build();

        CustomerOrderEntity saved = orderRepository.save(order);

        List<CustomerOrderItemEntity> items = new ArrayList<>();
        for (PlaceOrderRequest.OrderItemRequest item : request.getItems()) {
            items.add(CustomerOrderItemEntity.builder()
                    .uuid(UUID.randomUUID())
                    .customerOrderId(saved.getId())
                    .productUuid(item.getProductUuid())
                    .currency(saved.getCurrency())
                    .selectedDimension(item.getSelectedDimension())
                    .active(true)
                    .archive(false)
                    .build());
        }

        orderItemRepository.saveAll(items);
        log.info("Order {} placed by user {}", orderCode, customerId);

        return PlaceOrderResponse.builder()
                .orderUuid(saved.getUuid())
                .orderCode(orderCode)
                .build();
    }

    private String generateOrderCode() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return "VO-" + date + "-" + suffix;
    }
}
