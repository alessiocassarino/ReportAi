package com.claude.reportAi.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class JobHistoryDTO {
    private String jobId;
    private String type;              // DOCUMENTS | CONTRACTS | REPORTS
    private String originalFilename;
    private String status;            // PENDING, PROCESSING, COMPLETED, FAILED, etc.
    private String model;             // null per DOCUMENTS
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String errorMessage;
}
