package com.claude.reportAi.dto;

public record WebSearchResult(
        String title,
        String url,
        String content,
        Double score
) {

    public String toPromptBlock() {
        return """
                Titolo: %s
                URL: %s
                Score: %s
                Contenuto: %s
                """.formatted(
                safe(title),
                safe(url),
                score == null ? "n/a" : score,
                safe(content)
        );
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
}