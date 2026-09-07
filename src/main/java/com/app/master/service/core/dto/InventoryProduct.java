package com.app.master.service.core.dto;

import com.app.master.service.core.enums.InventoryCurrency;
import com.app.master.service.core.enums.InventoryGender;
import com.app.master.service.core.enums.InventoryVisibility;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class InventoryProduct {

    private UUID uuid;
    private String name;
    private String description;
    private String skuId;
    private Long price;
    private Long sellingPrice;
    private InventoryCurrency priceCurrency;
    private Long initialStock;
    private InventoryVisibility visibility;
    private Boolean draft;
    private InventoryGender gender;
    private String dimensions;
    private UUID supplierUuid;
    private String colour;
    private String wearType;
    private String hsnCode;
    private List<SizeStock> sizeStocks;
}