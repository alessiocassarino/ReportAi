package com.claude.reportAi.dto;

import lombok.Data;

@Data
public class ReportRequest {
    private String prompt;
    private String format; // JSON, CSV, XLSX
    private boolean allowWebSearch = true;
}
