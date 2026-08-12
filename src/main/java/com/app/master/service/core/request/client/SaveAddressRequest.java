package com.app.master.service.core.request.client;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SaveAddressRequest {
    @NotBlank private String receiverName;
    @NotBlank private String phone;
    @NotBlank private String address;
    private boolean isDefault;
}
