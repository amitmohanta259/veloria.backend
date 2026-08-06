package com.app.master.service.core.response.admin;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnsStatsResponse {

    private long totalReturns;
    private long totalOrders;
    private double returnRate;
    private long totalReturnValue;
}
