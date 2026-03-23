package com.claude.reportAi.dto;

import com.claude.reportAi.constant.ReportFormat;

public record ReportGenerationResult(
        String answer,
        ReportFormat resolvedFormat,
        String fileName,
        String downloadUrl
) {
}
