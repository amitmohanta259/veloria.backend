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

    public InventoryCollectionAllResponse(UUID uuid, String name, String description, Boolean active) {
        this.uuid = uuid;
        this.name = name;
        this.description = description;
        this.active = active;
    }
}