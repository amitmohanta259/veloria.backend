package com.app.master.service.core.request.client;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SendOtpRequest {

    @NotBlank
    private String identifier;

}
