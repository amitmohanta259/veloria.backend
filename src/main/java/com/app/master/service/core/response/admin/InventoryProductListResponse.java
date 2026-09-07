package com.app.master.service.core.response.admin;

import com.app.master.service.core.enums.InventoryCurrency;
import com.app.master.service.core.enums.InventoryGender;
import com.app.master.service.core.enums.InventoryVisibility;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.ALWAYS)
public class InventoryProductListResponse {

    private UUID uuid;
    private String name;
    private String description;
    private String skuId;
    private Long price;
    private Long sellingPrice;
    private InventoryCurrency priceCurrency;
    private Long initialStock;
    private InventoryVisibility visibility;
    private InventoryGender gender;
    private Boolean activeStatus;
    private String dimensions;
    private UUID supplierUuid;
    private List<String> images;
    private Long currentStock;

    public InventoryProductListResponse(UUID uuid, String name, String description, String skuId, Long price, Long sellingPrice,
                                        InventoryCurrency priceCurrency, Long initialStock, InventoryVisibility visibility,
                                        InventoryGender gender, Boolean activeStatus, String dimensions, UUID supplierUuid) {
        this.uuid = uuid;
        this.name = name;
        this.description = description;
        this.skuId = skuId;
        this.price = price;
        this.sellingPrice = sellingPrice;
        this.priceCurrency = priceCurrency;
        this.initialStock = initialStock;
        this.visibility = visibility;
        this.gender = gender;
        this.activeStatus = activeStatus;
        this.dimensions = dimensions;
        this.supplierUuid = supplierUuid;
    }

}
