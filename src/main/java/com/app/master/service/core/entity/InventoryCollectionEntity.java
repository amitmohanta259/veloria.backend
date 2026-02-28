package com.app.master.service.core.entity;

import com.app.master.service.core.dto.Base;
import com.app.master.service.core.dto.InventoryCollection;
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
@Table(name = "inventory_collection")
public class InventoryCollectionEntity extends Base {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    private Long categoryId;
    private String name;
    private String description;

    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Builder.Default
    private Boolean archive = Boolean.FALSE;

    public InventoryCollection toDto() {
        return InventoryCollection.builder()
                .uuid(this.uuid)
                .name(this.name)
                .description(this.description)
                .build();
    }

}