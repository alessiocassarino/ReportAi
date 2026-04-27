package com.claude.reportAi.dto.v2;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class WorkflowInfoResponse {

    private String id;
    private String displayName;
    private String description;
    private List<String> supportedSectors;
    private int minFiles;
    private int maxFiles;
    private String acceptedMimeType;
}
