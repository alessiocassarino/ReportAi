package com.claude.reportAi.entities;

import com.claude.reportAi.constant.DocumentType;
import com.claude.reportAi.constant.Language;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "document_metadata")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stored_file_id", nullable = false, unique = true)
    private StoredFile storedFile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Language language;

    private String country;

    private String clientName;

    private String projectName;

    private String sector;

    private String contractType;

    private LocalDate documentDate;

    private String documentVersion;

    @Column(columnDefinition = "TEXT")
    private String tagsJson;

    @Column(nullable = false)
    private Instant createdAt;
}
