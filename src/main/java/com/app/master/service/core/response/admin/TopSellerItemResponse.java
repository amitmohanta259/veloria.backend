package com.app.master.service.core.response.admin;

import lombok.*;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class TopSellerItemResponse {
    private String name;
    private long salesCount;
    private long totalRevenue;
    private String currency;
    private String imageUrl;
}
