package com.app.master.service.core.response.client;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerCartItemResponse {
    private UUID productUuid;
    private String productName;
    private String productImage;
    private Long price;
    private Long sellingPrice;
    private String priceCurrency;
    private String category;
    private Integer quantity;
    private Instant addedAt;
}
