package com.app.master.service.core.response.admin;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerSummaryResponse {
    private String customerId;
    private String name;
    private String email;
    private String phone;
    private String joiningDate;
    private long purchases;
    private long cancellationsReturns;
    private long lifetimeValue;
    private String status;
}
