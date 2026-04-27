package com.claude.reportAi.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Unified job record for the v2 pipeline engine.
 * Acts as a routing record: workflowId + legacyJobId point to the actual processing entity.
 */
@Entity
@Table(name = "job",
        indexes = {
                @Index(name = "idx_job_status", columnList = "status"),
                @Index(name = "idx_job_workflow_id", columnList = "workflow_id"),
                @Index(name = "idx_job_created_by", columnList = "created_by"),
                @Index(name = "idx_job_created_at", columnList = "created_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_id", nullable = false, length = 100)
    private String workflowId;

    @Column(nullable = false, length = 50)
    private String sector = "OIL_GAS";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status = JobStatus.PENDING;

    @Column(nullable = false)
    private int progress = 0;

    @Column(name = "current_step", columnDefinition = "TEXT")
    private String currentStep;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(length = 100)
    private String model;

    @Column(name = "input_filenames", columnDefinition = "TEXT")
    private String inputFilenames;

    @Column(name = "num_files")
    private int numFiles = 1;

    /**
     * UUID of the legacy entity (ContractAnalysis, Estimate, or PriceComparison).
     * Used to delegate status/result reads to the actual processor entity.
     */
    @Column(name = "legacy_job_id", length = 36)
    private String legacyJobId;

    /**
     * Discriminator for the legacy entity type: CONTRACT, ESTIMATE, PRICE_COMPARISON.
     */
    @Column(name = "legacy_type", length = 30)
    private String legacyType;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum JobStatus {
        PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED
    }
}
