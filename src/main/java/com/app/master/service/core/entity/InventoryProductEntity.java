package com.app.master.service.core.entity;

import com.app.master.service.core.dto.Base;
import com.app.master.service.core.dto.InventoryProduct;
import com.app.master.service.core.enums.InventoryCurrency;
import com.app.master.service.core.enums.InventoryGender;
import com.app.master.service.core.enums.InventoryVisibility;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "inventory_product")
public class InventoryProductEntity extends Base {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    private Long subCategoryId;
    private String name;
    private String description;
    private String skuId;
    private Long price;
    @Column(name = "selling_price")
    private Long sellingPrice;
    private String dimensions;

    @Enumerated(EnumType.STRING)
    private InventoryCurrency priceCurrency;

    private Long initialStock;

    @Enumerated(EnumType.STRING)
    private InventoryVisibility visibility;

    private Instant visibilityDate;
    private Boolean draft;

    @Enumerated(EnumType.STRING)
    private InventoryGender gender;

    private UUID supplierUuid;
    private String colour;

    @Column(name = "wear_type")
    private String wearType;

    @Column(name = "hsn_code")
    private String hsnCode;

    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Builder.Default
    private Boolean archive = Boolean.FALSE;

    public InventoryProduct toDto() {
        return InventoryProduct.builder()
                .uuid(this.uuid)
                .name(this.name)
                .description(this.description)
                .skuId(this.skuId)
                .price(this.price)
                .sellingPrice(this.sellingPrice)
                .priceCurrency(this.priceCurrency)
                .initialStock(this.initialStock)
                .visibility(this.visibility)
                .draft(this.draft)
                .gender(this.gender)
                .dimensions(this.dimensions)
                .supplierUuid(this.supplierUuid)
                .colour(this.colour)
                .wearType(this.wearType)
                .hsnCode(this.hsnCode)
                .build();
    }

}