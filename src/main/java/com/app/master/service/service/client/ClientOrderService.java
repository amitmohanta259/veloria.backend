package com.app.master.service.service.client;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.PlaceOrderRequest;
import com.app.master.service.core.response.client.OrderConfirmationResponse;
import com.app.master.service.core.response.client.OrderHistoryResponse;
import com.app.master.service.core.response.client.PlaceOrderResponse;

import java.util.List;

public interface ClientOrderService {
    PlaceOrderResponse placeOrder(String token, PlaceOrderRequest request) throws VeloriaException;
    /**
     * The order behind a code, for the buyer who placed it.
     *
     * The token is required: an order code is an identifier, not a secret, and
     * must never be the only thing standing between one customer and another
     * customer's name, address, items and tax detail.
     */
    OrderConfirmationResponse getOrderConfirmation(String token, String orderCode) throws VeloriaException;
    List<OrderHistoryResponse> getOrderHistory(String token, Integer year) throws VeloriaException;
    String recordFailedOrder(String token, PlaceOrderRequest request) throws VeloriaException;
}
