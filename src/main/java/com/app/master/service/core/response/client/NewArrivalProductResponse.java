package com.app.master.service.core.response.client;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewArrivalProductResponse {
    private Long id;
    private UUID uuid;
    private String name;
    private String description;
    private Long price;
    private String priceCurrency;
    private String dimensions;
    private String categoryName;
    private String category;
    private Long sellingPrice;
    private String image;
}
