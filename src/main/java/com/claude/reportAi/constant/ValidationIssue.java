package com.claude.reportAi.constant;

public enum ValidationIssue {
    TOO_SHORT("Output too short"),
    TOO_LONG("Output too long"),
    POSSIBLE_HALLUCINATION("Possible AI hallucination detected"),
    POOR_STRUCTURE("Output structure is poor"),
    FORMAT_INVALID("Output format is invalid"),
    EMPTY_OUTPUT("Output is empty"),
    ENCODING_ERROR("Encoding error detected");

    private final String description;

    ValidationIssue(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
