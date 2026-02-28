package com.app.master.service.core.entity;

import com.app.master.service.core.dto.Base;
import com.app.master.service.core.dto.InventorySubCategory;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "inventory_sub_category")
public class InventorySubCategoryEntity extends Base {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    private Long collectionId;
    private String name;
    private String description;

    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Builder.Default
    private Boolean archive = Boolean.FALSE;

    public InventorySubCategory toDto() {
        return InventorySubCategory.builder()
                .uuid(this.uuid)
                .name(this.name)
                .description(this.description)
                .build();
    }

}