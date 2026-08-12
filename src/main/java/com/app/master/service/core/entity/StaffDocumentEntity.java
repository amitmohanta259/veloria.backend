package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "staff_documents")
public class StaffDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long staffId;
    private String category;
    private Long recordId;
    private String label;
    private String documentKey;

    @Builder.Default
    private LocalDateTime uploadedAt = LocalDateTime.now();
}
