package com.claude.reportAi.service;

import com.claude.reportAi.dto.OutputValidationResult;
import com.claude.reportAi.constant.ValidationIssue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@Slf4j
public class OutputValidationService {

    private static final int MIN_LENGTH = 100;
    private static final int MAX_LENGTH = 50000;
    private static final List<String> HALLUCINATION_KEYWORDS = Arrays.asList(
            "i don't know",
            "non so",
            "fictitious",
            "esempio",
            "immaginario",
            "non ho informazioni",
            "unfortunately i cannot",
            "i cannot provide",
            "unable to",
            "non posso fornire",
            "i don't have access",
            "non ho accesso"
    );

    public OutputValidationResult validate(String output, boolean contextFound) {
        ValidationScore score = new ValidationScore();

        log.info("Starting output validation -> contextFound={}, length={}", 
                contextFound, output != null ? output.length() : 0);

        if (output == null || output.isBlank()) {
            score.addIssue(ValidationIssue.EMPTY_OUTPUT);
            score.decreaseScore(1.0);
            log.warn("Output is empty");
            return new OutputValidationResult(
                    score.getQualityScore(),
                    score.getIssues(),
                    contextFound
            );
        }

        // 1. Length validation
        if (output.length() < MIN_LENGTH) {
            score.addIssue(ValidationIssue.TOO_SHORT);
            score.decreaseScore(0.15);
            log.warn("Output too short -> length={}", output.length());
        }
        if (output.length() > MAX_LENGTH) {
            score.addIssue(ValidationIssue.TOO_LONG);
            score.decreaseScore(0.10);
            log.warn("Output too long -> length={}", output.length());
        }

        // 2. Hallucination detection
        if (!contextFound && hasHallucinationIndicators(output)) {
            score.addIssue(ValidationIssue.POSSIBLE_HALLUCINATION);
            score.decreaseScore(0.30);
            log.warn("Possible hallucination detected without context");
        }

        // 3. Structure validation
        if (!hasExpectedStructure(output)) {
            score.addIssue(ValidationIssue.POOR_STRUCTURE);
            score.decreaseScore(0.10);
            log.warn("Poor output structure detected");
        }

        // 4. Format validation
        if (!isWellFormatted(output)) {
            score.addIssue(ValidationIssue.FORMAT_INVALID);
            score.decreaseScore(0.20);
            log.warn("Invalid format detected");
        }

        log.info("Output validation completed -> score={}, issues={}", 
                score.getQualityScore(), score.getIssues().size());

        return new OutputValidationResult(
                score.getQualityScore(),
                score.getIssues(),
                contextFound
        );
    }

    private boolean hasHallucinationIndicators(String output) {
        String lower = output.toLowerCase();
        return HALLUCINATION_KEYWORDS.stream()
                .anyMatch(lower::contains);
    }

    private boolean hasExpectedStructure(String output) {
        // Must contain at least one of:
        // - Headers (# ## ###)
        // - Lists (* - •)
        // - Tables (| |)
        // - Paragraphs (minimo 3 newlines)
        return output.contains("#") || 
               output.contains("*") || 
               output.contains("|") || 
               output.split("\n\n").length >= 3;
    }

    private boolean isWellFormatted(String output) {
        int openParen = countChar(output, '(');
        int closeParen = countChar(output, ')');
        int openBracket = countChar(output, '[');
        int closeBracket = countChar(output, ']');
        int openBrace = countChar(output, '{');
        int closeBrace = countChar(output, '}');

        return openParen == closeParen && 
               openBracket == closeBracket && 
               openBrace == closeBrace;
    }

    private int countChar(String str, char c) {
        return (int) str.chars().filter(ch -> ch == c).count();
    }

    private static class ValidationScore {
        private Double score = 1.0;
        private final List<ValidationIssue> issues = new ArrayList<>();

        void decreaseScore(double amount) {
            score = Math.max(0.0, score - amount);
        }

        void addIssue(ValidationIssue issue) {
            issues.add(issue);
        }

        Double getQualityScore() {
            return score;
        }

        List<ValidationIssue> getIssues() {
            return issues;
        }
    }
}
