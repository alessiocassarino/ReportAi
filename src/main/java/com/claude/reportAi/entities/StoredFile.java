package com.claude.reportAi.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stored_file")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoredFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String originalFilename;

    private String contentType;

    @Column(nullable = false, unique = true, length = 64)
    private String sha256;

    @Column(nullable = false)
    private Long sizeBytes;

    @Column(nullable = false)
    private String storagePath;

    @Column(columnDefinition = "BYTEA")
    private byte[] content;

    @Column(columnDefinition = "TEXT")
    private String extractedText;

    @Column(nullable = false)
    private String extractionStatus;

    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    @CreationTimestamp
    private Instant createdAt;
}
