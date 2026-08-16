package com.app.master.service.service.client;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.AddToCartRequest;
import com.app.master.service.core.response.client.CustomerCartItemResponse;

import java.util.List;
import java.util.UUID;

public interface ClientCartService {
    List<CustomerCartItemResponse> getCart(String token) throws VeloriaException;
    void addToCart(String token, AddToCartRequest request) throws VeloriaException;
    void updateQuantity(String token, UUID productUuid, int quantity) throws VeloriaException;
    void removeFromCart(String token, UUID productUuid) throws VeloriaException;
}
