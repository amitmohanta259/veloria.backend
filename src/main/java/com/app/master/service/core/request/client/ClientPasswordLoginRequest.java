package com.app.master.service.core.request.client;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ClientPasswordLoginRequest {

    @Email
    @NotBlank
    private String email;

    @NotBlank
    private String password;

}
