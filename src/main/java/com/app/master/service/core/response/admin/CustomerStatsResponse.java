package com.app.master.service.core.response.admin;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerStatsResponse {

    private long totalOrders;
    private long totalLifetimeValue;
    private long averageOrderValue;
}
