package com.app.master.service.service.client;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.PlaceOrderRequest;
import com.app.master.service.core.response.client.OrderConfirmationResponse;
import com.app.master.service.core.response.client.OrderHistoryResponse;
import com.app.master.service.core.response.client.PlaceOrderResponse;

import java.util.List;

public interface ClientOrderService {
    PlaceOrderResponse placeOrder(String token, PlaceOrderRequest request) throws VeloriaException;
    OrderConfirmationResponse getOrderConfirmation(String orderCode) throws VeloriaException;
    List<OrderHistoryResponse> getOrderHistory(String token, Integer year) throws VeloriaException;
    String recordFailedOrder(String token, PlaceOrderRequest request) throws VeloriaException;
}
