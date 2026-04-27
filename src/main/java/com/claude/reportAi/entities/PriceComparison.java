package com.claude.reportAi.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "price_comparison")
@Getter
@Setter
@NoArgsConstructor
public class PriceComparison {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status = JobStatus.PENDING;

    @Column(nullable = false)
    private int progress = 0;

    @Column(columnDefinition = "TEXT")
    private String currentStep;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private String resultFileName;

    @Column(columnDefinition = "BYTEA")
    private byte[] resultFileContent;

    @Column(nullable = false)
    private String model = "claude-haiku-4-5-20251001";

    @Column(columnDefinition = "TEXT")
    private String originalFilenames;

    @Column(nullable = false)
    private int numFiles = 0;

    @Column(nullable = false, length = 50)
    private String sector = "OIL_GAS";

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    /**
     * JSON array con i dati di debug per ogni file elaborato nella Fase 1.
     * Struttura: [{file, chars, lowText, images, textPreview, json}, ...]
     * Persiste il supplierJson grezzo per file, utile per capire dove si rompe
     * la pipeline (estrazione LLM vs merge nel confronto).
     */
    @Column(columnDefinition = "TEXT")
    private String debugSupplierJsons;

    /**
     * JSON grezzo restituito dal secondo LLM (confronto comparativo),
     * prima che venga passato al ReportBuilder per generare il DOCX.
     * Permette di verificare se i dati sono persi nella fase di merge
     * o nella fase di generazione documento.
     */
    @Column(columnDefinition = "TEXT")
    private String debugComparisonJson;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public enum JobStatus {
        PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED
    }
}
