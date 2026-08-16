package com.app.master.service.core.response.client;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Builder
public class CustomerFavouriteResponse {
    private UUID productUuid;
    private String productName;
    private String productImage;
    private Long price;
    private Long sellingPrice;
    private String priceCurrency;
    private String category;
    private Instant addedAt;
    private String stockStatus;
}
