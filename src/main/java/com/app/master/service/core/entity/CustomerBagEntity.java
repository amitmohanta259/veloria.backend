package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer_bag")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerBagEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    private String userId;

    private UUID productUuid;

    @Column(nullable = false)
    private Integer quantity;

    private String size;
    private Instant addedAt;
    private Instant updatedAt;

    @Builder.Default
    private Boolean archive = false;
}
