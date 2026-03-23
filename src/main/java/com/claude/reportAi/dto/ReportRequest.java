package com.claude.reportAi.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportRequest {

    @NotBlank
    private String prompt;

    /**
     * AUTO | TEXT | CSV | XLSX | DOCX | PPTX
     */
    @Builder.Default
    private String format = "AUTO";

    @Builder.Default
    private boolean allowWebSearch = false;

    /**
     * Filtro metadata portabile Spring AI, opzionale.
     * Esempio:
     * industry == 'oil-gas' && region == 'MEA'
     */
    private String knowledgeFilterExpression;

    @Builder.Default
    private Integer topK = 6;

    @Builder.Default
    private Double similarityThreshold = 0.75d;
}