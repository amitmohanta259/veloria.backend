package com.app.master.service.controller.client;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.entity.OrderReturnRequestEntity;
import com.app.master.service.core.entity.CustomerOrderEntity;
import com.app.master.service.core.entity.CustomerOrderItemEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.PlaceOrderRequest;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.admin.CustomerOrderItemRepository;
import com.app.master.service.repository.admin.CustomerOrderRepository;
import com.app.master.service.repository.admin.OrderReturnRequestRepository;
import com.app.master.service.service.client.ClientOrderService;
import com.app.master.service.service.client.ClientSessionStore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/client")
@RequiredArgsConstructor
public class ClientOrderController extends AppController {

    private final ClientOrderService clientOrderService;
    private final ClientSessionStore sessionStore;
    private final CustomerOrderRepository customerOrderRepository;
    private final CustomerOrderItemRepository customerOrderItemRepository;
    private final OrderReturnRequestRepository returnRequestRepository;

    @PostMapping("/order/place")
    public ResponseEntity<Response> placeOrder(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody PlaceOrderRequest request) throws VeloriaException {
        String token = authHeader.replace("Bearer ", "").trim();
        return success(ResponseCode.OK, "Order placed successfully", clientOrderService.placeOrder(token, request));
    }

    @GetMapping("/order/{orderCode}")
    public ResponseEntity<Response> getOrderConfirmation(@PathVariable String orderCode) throws VeloriaException {
        return success(ResponseCode.FETCHED, "Order details fetched", clientOrderService.getOrderConfirmation(orderCode));
    }

    @GetMapping("/order/history")
    public ResponseEntity<Response> getOrderHistory(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) Integer year) throws VeloriaException {
        String token = authHeader.replace("Bearer ", "").trim();
        return success(ResponseCode.FETCHED, "Order history fetched", clientOrderService.getOrderHistory(token, year));
    }

    @PostMapping("/order/record-failed")
    public ResponseEntity<Response> recordFailedOrder(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody PlaceOrderRequest request) throws VeloriaException {
        String token = authHeader.replace("Bearer ", "").trim();
        String orderCode = clientOrderService.recordFailedOrder(token, request);
        return success(ResponseCode.OK, "Failed order recorded", Map.of("orderCode", orderCode));
    }

    @PostMapping("/order/{orderCode}/return")
    @Transactional
    public ResponseEntity<Response> submitReturn(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable String orderCode,
            @RequestBody Map<String, String> body) throws VeloriaException {
        String token = authHeader.replace("Bearer ", "").trim();
        ClientSessionStore.SessionData session = sessionStore.get(token);
        if (session == null) throw new VeloriaException(ResponseCode.UNAUTHORIZED, "Session expired");

        CustomerOrderEntity order = customerOrderRepository.findByOrderCodeAndArchiveFalse(orderCode)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "Order not found"));

        if (!"DELIVERED".equals(order.getStatus()))
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "Only delivered orders can be returned");

        String returnType = body.getOrDefault("returnType", "RETURN");
        String reason     = body.getOrDefault("reason", "");
        String frontImage = body.get("frontImage");
        String backImage  = body.get("backImage");
        String tagImage   = body.get("tagImage");

        // Mark all items in this order as RETURNED with reason
        List<CustomerOrderItemEntity> items =
                customerOrderItemRepository.findByCustomerOrderIdAndArchiveFalseOrderByIdAsc(order.getId());
        for (CustomerOrderItemEntity item : items) {
            item.setReasonForReturn(reason);
            customerOrderItemRepository.save(item);
        }

        // Create return request record
        String firstItemUuid = items.isEmpty() ? null : items.get(0).getUuid().toString();
        returnRequestRepository.save(OrderReturnRequestEntity.builder()
                .orderCode(orderCode)
                .customerId(session.userId())
                .itemUuid(firstItemUuid)
                .returnType(returnType)
                .reason(reason)
                .frontImage(frontImage)
                .backImage(backImage)
                .tagImage(tagImage)
                .build());

        // Update order status
        order.setStatus("READY_TO_PICKUP");
        customerOrderRepository.save(order);

        return success(ResponseCode.OK, "Return request submitted successfully", null);
    }
}
