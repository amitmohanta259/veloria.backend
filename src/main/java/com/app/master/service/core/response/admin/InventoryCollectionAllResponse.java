package com.app.master.service.core.response.admin;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class InventoryCollectionAllResponse {

    private UUID uuid;
    private String name;
    private String description;
    private Boolean active;
    private String parentName;
    private Long soldQty;
    private Long inInventory;

    public InventoryCollectionAllResponse(UUID uuid, String name, String description, Boolean active) {
        this.uuid = uuid;
        this.name = name;
        this.description = description;
        this.active = active;
    }

    public InventoryCollectionAllResponse(UUID uuid, String name, String description, Boolean active, String parentName) {
        this.uuid = uuid;
        this.name = name;
        this.description = description;
        this.active = active;
        this.parentName = parentName;
    }
}