package com.app.master.service.service.client;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.AddToCartRequest;
import com.app.master.service.core.response.client.CustomerBagItemResponse;

import java.util.List;
import java.util.UUID;

public interface ClientBagService {
    List<CustomerBagItemResponse> getBag(String token) throws VeloriaException;
    void addToBag(String token, AddToCartRequest request) throws VeloriaException;
    void updateQuantity(String token, UUID productUuid, int quantity) throws VeloriaException;
    void removeFromBag(String token, UUID productUuid) throws VeloriaException;
}
