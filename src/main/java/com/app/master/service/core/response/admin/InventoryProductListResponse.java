package com.app.master.service.core.response.admin;

import com.app.master.service.core.enums.InventoryCurrency;
import com.app.master.service.core.enums.InventoryVisibility;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class InventoryProductListResponse {

    private UUID uuid;
    private String name;
    private String description;
    private String skuId;
    private Long price;
    private InventoryCurrency priceCurrency;
    private Long initialStock;
    private InventoryVisibility visibility;
    private Boolean activeStatus;
    private List<String> images;

    public InventoryProductListResponse(UUID uuid, String name, String description, String skuId, Long price, InventoryCurrency priceCurrency,
                                        Long initialStock, InventoryVisibility visibility, Boolean activeStatus) {
        this.uuid = uuid;
        this.name = name;
        this.description = description;
        this.skuId = skuId;
        this.price = price;
        this.priceCurrency = priceCurrency;
        this.initialStock = initialStock;
        this.visibility = visibility;
        this.activeStatus = activeStatus;
    }

}