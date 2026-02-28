package com.app.master.service.core.dto;

import lombok.*;

import java.util.UUID;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class InventorySubCategory {

    private UUID collectionUuid;
    private UUID uuid;
    private String name;
    private String description;
}