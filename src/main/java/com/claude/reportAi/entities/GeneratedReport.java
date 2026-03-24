package com.claude.reportAi.entities;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "generated_reports")
@Data
public class GeneratedReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID del cliente proprietario del report */
    @Column(nullable = false)
    private String clientId;

    /** Nome del file (es. "sales_report.xlsx") */
    @Column(nullable = false)
    private String fileName;

    /** Tipo MIME (es. "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") */
    @Column(nullable = false)
    private String mimeType;

    /** Tipo di skill usata (XLSX, PPTX, DOCX, PDF) */
    @Column(nullable = false)
    private String skillType;

    /** Il file binario, salvato direttamente nel DB */
    @Lob
    @Column(nullable = false, columnDefinition = "BYTEA")
    private byte[] fileContent;

    /** Dimensione in bytes */
    private long fileSize;

    /** Il prompt usato per generare il report */
    @Column(columnDefinition = "TEXT")
    private String prompt;

    /** Testo della risposta di Claude (metadati) */
    @Column(columnDefinition = "TEXT")
    private String responseText;

    /** Conferma che il file è stato cancellato da Anthropic */
    @Column(nullable = false)
    private boolean deletedFromAnthropic = false;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

}
