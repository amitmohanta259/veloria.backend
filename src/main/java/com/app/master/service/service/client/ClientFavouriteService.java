package com.app.master.service.service.client;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.client.CustomerFavouriteResponse;

import java.util.List;
import java.util.UUID;

public interface ClientFavouriteService {
    List<CustomerFavouriteResponse> getFavourites(String token) throws VeloriaException;
    List<String> getFavouriteUuids(String token) throws VeloriaException;
    void addFavourite(String token, UUID productUuid) throws VeloriaException;
    void removeFavourite(String token, UUID productUuid) throws VeloriaException;
}
