package com.app.master.service.core.dto;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesOrderRequest {

    private UUID productUuid;
    private String customerName;
    private String customerEmail;
    private Integer quantity;
    private String currency;
}
