package com.app.master.service.core.response.admin;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SupplierStatsResponse {

    private long totalPartners;
    private long activeContracts;
    private long pendingReview;
    private long terminated;
}
