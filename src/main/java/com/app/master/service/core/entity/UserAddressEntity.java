package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_address")
public class UserAddressEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    private String userId;

    private String receiverName;

    private String phone;

    @Column(columnDefinition = "TEXT")
    private String address;

    private Boolean isDefault;

    private Boolean active;

    private Boolean archive;

    private Instant createdAt;
}
