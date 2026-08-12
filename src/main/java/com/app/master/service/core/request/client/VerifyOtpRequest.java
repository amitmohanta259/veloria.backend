package com.app.master.service.core.request.client;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VerifyOtpRequest {

    @NotBlank
    private String identifier;

    @NotBlank
    private String otp;

}
