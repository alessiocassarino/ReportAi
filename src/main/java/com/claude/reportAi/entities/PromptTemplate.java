package com.claude.reportAi.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Stores LLM system prompts per workflow + sector combination.
 * Allows prompt changes without code redeploy and multi-sector support.
 */
@Entity
@Table(name = "prompt_template",
        indexes = {
                @Index(name = "idx_prompt_key_sector", columnList = "template_key, sector, active")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_prompt_key_sector_version",
                        columnNames = {"template_key", "sector", "version"})
        })
@Getter
@Setter
@NoArgsConstructor
public class PromptTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Logical key, e.g. "contract-risk-section-analysis", "estimate-generation-system".
     */
    @Column(name = "template_key", nullable = false, length = 200)
    private String templateKey;

    /**
     * Which workflow this prompt belongs to, e.g. "contract-risk-analysis".
     */
    @Column(name = "workflow_id", nullable = false, length = 100)
    private String workflowId;

    /**
     * Sector this prompt is tailored for, e.g. "OIL_GAS", "PHARMA", "LEGAL".
     */
    @Column(nullable = false, length = 50)
    private String sector;

    @Column(nullable = false, length = 20)
    private String version = "v1";

    @Column(nullable = false)
    private boolean active = true;

    /**
     * "SYSTEM" or "USER".
     */
    @Column(nullable = false, length = 10)
    private String role = "SYSTEM";

    @Column(name = "prompt_name", length = 200)
    private String promptName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
