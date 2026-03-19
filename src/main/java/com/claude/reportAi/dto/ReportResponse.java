package com.claude.reportAi.dto;

import lombok.*;


@Data
@AllArgsConstructor
@Builder
public class ReportResponse {
    private String status;
    private boolean foundInKnowledgeBase;
    private boolean webSearchUsed;
    private String answer;
    private String fileName;
    private String downloadUrl;
}
