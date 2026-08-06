package com.app.master.service.core.response.admin;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesStatsResponse {

    private long orderPlaced;
    private long inTransit;
    private long done;
    private long totalRevenue;
}
