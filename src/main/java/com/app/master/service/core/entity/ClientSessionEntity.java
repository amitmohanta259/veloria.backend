package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "client_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(name = "user_id", nullable = false)
    private String userId;

    private String name;
    private String email;
    private String phone;

    @Column(nullable = false)
    private Instant expiry;
}
