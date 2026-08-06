package com.app.master.service.core.response.admin;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceLedgerResponse {

    private UUID productUuid;
    private String category;
    private String collection;
    private String subCategory;
    private String productName;
    private long sales;
    private long returns;
    private long intent;
    private double conversion;
}
