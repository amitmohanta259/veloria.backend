package com.app.master.service.core.response.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class AddressBookEntry {
    private UUID uuid;
    private String receiverName;
    private String phone;
    private String address;
    @JsonProperty("isDefault")
    private boolean isDefault;
}
