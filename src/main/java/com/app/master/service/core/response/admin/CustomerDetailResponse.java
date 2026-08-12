package com.app.master.service.core.response.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Builder
public class CustomerDetailResponse {
    private String name;
    private String email;
    private String phone;
    private Instant joiningDate;

    private List<Address> addresses;

    @Getter
    @Builder
    public static class Address {
        private UUID uuid;
        private String receiverName;
        private String phone;
        private String address;
        @JsonProperty("isDefault")
        private boolean isDefault;
    }
}
