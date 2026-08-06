package com.app.master.service.core.response.admin;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerOrderItemResponse {

    private UUID uuid;
    private UUID productUuid;
    private String productName;
    private String productImageUrl;
    private Long price;
    private String currency;
    private String dimensions;
    private String comment;
    private Integer rating;
    private String reasonForReturn;
}
