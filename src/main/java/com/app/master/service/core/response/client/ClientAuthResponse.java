package com.app.master.service.core.response.client;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ClientAuthResponse {

    private String token;
    private String userId;
    private String name;
    private String email;
    private String phone;

}
