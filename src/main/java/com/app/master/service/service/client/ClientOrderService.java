package com.app.master.service.service.client;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.PlaceOrderRequest;
import com.app.master.service.core.response.client.PlaceOrderResponse;

public interface ClientOrderService {
    PlaceOrderResponse placeOrder(String token, PlaceOrderRequest request) throws VeloriaException;
}
