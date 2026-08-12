package com.app.master.service.core.response.client;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProfileResponse {
    private String userId;
    private String firstName;
    private String middleName;
    private String lastName;
    private String email;
    private String phone;
}
