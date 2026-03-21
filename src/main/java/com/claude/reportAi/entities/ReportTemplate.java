package com.claude.reportAi.entities;

import com.claude.reportAi.constant.TemplateType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "report_template")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TemplateType templateType;

    @Column(nullable = false)
    private String originalFilename;

    private String contentType;

    @Column(nullable = false, unique = true, length = 64)
    private String sha256;

    @Column(nullable = false)
    private String storagePath;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private Integer version;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant updatedAt;
}
