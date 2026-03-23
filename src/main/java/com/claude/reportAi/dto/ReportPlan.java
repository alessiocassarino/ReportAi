package com.claude.reportAi.dto;

import com.claude.reportAi.constant.ReportFormat;

public record ReportPlan(
        ReportFormat format,
        String retrievalQuery,
        boolean webSearchRecommended,
        String title
) {
}
