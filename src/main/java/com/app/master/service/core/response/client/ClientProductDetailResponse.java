package com.app.master.service.core.response.client;

import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientProductDetailResponse {
    private Long id;
    private UUID uuid;
    private String name;
    private String description;
    private Long price;
    private Long sellingPrice;
    private String priceCurrency;
    private String collectionName;
    private String categoryName;
    private String category;
    private String dimensions;
    private String mainImage;
    private List<String> gallery;
}
