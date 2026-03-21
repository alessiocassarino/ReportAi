package com.claude.reportAi.dto;

import lombok.*;

@Data
@Builder
public class TemplateSummaryResponse {
    private Long id;
    private String name;
    private String code;
    private String templateType;
    private String originalFilename;
    private boolean active;
    private Integer version;
}
