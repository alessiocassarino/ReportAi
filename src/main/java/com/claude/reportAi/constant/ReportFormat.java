package com.claude.reportAi.constant;

import java.util.Locale;

public enum ReportFormat {
    AUTO,
    TEXT,
    CSV,
    XLSX,
    DOCX,
    PPTX;

    public static ReportFormat from(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return ReportFormat.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            return AUTO;
        }
    }

    public boolean requiresAnthropicSkill() {
        return this == XLSX || this == DOCX || this == PPTX;
    }

    public boolean requiresLocalExport() {
        return this == CSV;
    }
}
