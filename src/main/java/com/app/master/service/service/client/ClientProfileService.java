package com.app.master.service.service.client;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.SaveAddressRequest;
import com.app.master.service.core.response.client.AddressBookEntry;
import com.app.master.service.core.response.client.ProfileResponse;

import java.util.List;

public interface ClientProfileService {
    ProfileResponse getProfile(String token) throws VeloriaException;
    List<AddressBookEntry> getAddressBook(String token) throws VeloriaException;
    AddressBookEntry saveAddress(String token, SaveAddressRequest request) throws VeloriaException;
}
