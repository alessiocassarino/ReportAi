package com.claude.reportAi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportResponse {

    private String status;
    private boolean foundInKnowledgeBase;
    private boolean webSearchUsed;

    /**
     * TEXT | CSV | XLSX | DOCX | PPTX
     */
    private String resolvedFormat;

    private String answer;
    private String fileName;
    private String downloadUrl;
}