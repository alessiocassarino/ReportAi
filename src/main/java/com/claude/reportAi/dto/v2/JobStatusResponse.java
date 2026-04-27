package com.claude.reportAi.dto.v2;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class JobStatusResponse {

    private UUID jobId;
    private String workflowId;
    private String workflowDisplayName;
    private String sector;
    private String status;
    private int progress;
    private String currentStep;
    private String errorMessage;
    private String model;
    private String inputFilenames;
    private int numFiles;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
