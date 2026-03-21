package com.claude.reportAi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 📊 DTO per l'output renderizzato di un report con template
 *
 * Contiene le informazioni sul file generato dopo l'applicazione di un template.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RenderedOutput {

    /**
     * Nome del file generato (es: "550e8400-e29b-41d4-a716-446655440000.xlsx")
     */
    private String fileName;

    /**
     * Content-Type del file (es: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
     */
    private String contentType;

    /**
     * URL relativo per scaricare il file
     * Formato: "/api/reports/download/{fileName}"
     */
    private String downloadUrl;

    /**
     * 🎨 Indica se un template è stato effettivamente utilizzato per il rendering
     */
    @Builder.Default
    private boolean templateUsed = false;

    /**
     * 🎯 ID del template utilizzato (se templateUsed = true)
     */
    private Long templateId;

    /**
     * 📋 Tipo di template utilizzato (EXCEL, DOCX, CSV, etc.)
     */
    private String templateType;
}
