package com.claude.reportAi.service;

import com.claude.reportAi.dto.AssembledContext;
import com.claude.reportAi.dto.ContextQualityMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContextQualityService {

    public ContextQualityMetrics scoreContext(AssembledContext context, String userQuery) {
        log.info("Calculating context quality score");

        ContextQualityMetrics metrics = new ContextQualityMetrics();

        if (context == null) {
            log.warn("Context is null, assigning zero score");
            metrics.setAverageSimilarity(0.0);
            metrics.setTotalDocuments(0);
            metrics.setMetadataMatched(false);
            metrics.setCompletenessScore(0.0);
            metrics.setFreshnessScore(0.0);
            metrics.setRelevanceScore(0.0);
            metrics.setQualitySummary("No context available");
            return metrics;
        }

        // 1. Count total documents
        int totalDocs = getTotalDocuments(context);
        metrics.setTotalDocuments(totalDocs);

        // 2. Calculate average similarity
        double avgSimilarity = calculateAverageSimilarity(context);
        metrics.setAverageSimilarity(avgSimilarity);

        // 3. Check metadata matching
        boolean metadataMatched = hasRelevantMetadata(context);
        metrics.setMetadataMatched(metadataMatched);

        // 4. Calculate completeness score
        double completenessScore = calculateCompletenessScore(context, userQuery);
        metrics.setCompletenessScore(completenessScore);

        // 5. Calculate freshness score
        double freshnessScore = calculateFreshnessScore(context);
        metrics.setFreshnessScore(freshnessScore);

        // 6. Calculate relevance score
        double relevanceScore = calculateRelevanceScore(context, userQuery);
        metrics.setRelevanceScore(relevanceScore);

        // 7. Generate summary
        String summary = generateQualitySummary(metrics);
        metrics.setQualitySummary(summary);

        log.info("Context quality score calculated -> avgSimilarity={}, totalDocs={}, overallScore={}",
                String.format("%.2f", avgSimilarity),
                totalDocs,
                String.format("%.2f", metrics.getOverallScore()));

        return metrics;
    }

    private int getTotalDocuments(AssembledContext context) {
        int count = 0;
        if (context.getKnowledgeBaseDocuments() != null) {
            count += context.getKnowledgeBaseDocuments().size();
        }
        if (context.getTemporaryDocuments() != null) {
            count += context.getTemporaryDocuments().size();
        }
        if (context.getReferenceDataDocuments() != null) {
            count += context.getReferenceDataDocuments().size();
        }
        if (context.getWebResults() != null) {
            count += context.getWebResults().size();
        }
        return count;
    }

    private double calculateAverageSimilarity(AssembledContext context) {
        double totalSimilarity = 0.0;
        int count = 0;

        List<Document> allDocs = new java.util.ArrayList<>();
        if (context.getKnowledgeBaseDocuments() != null) {
            allDocs.addAll(context.getKnowledgeBaseDocuments());
        }
        if (context.getTemporaryDocuments() != null) {
            allDocs.addAll(context.getTemporaryDocuments());
        }
        if (context.getReferenceDataDocuments() != null) {
            allDocs.addAll(context.getReferenceDataDocuments());
        }

        for (Document doc : allDocs) {
            if (doc.getMetadata() != null && doc.getMetadata().containsKey("distance")) {
                Object distanceObj = doc.getMetadata().get("distance");
                if (distanceObj instanceof Number) {
                    // distance is usually 0-1, where 1 is most similar
                    // convert to similarity: similarity = 1 - distance
                    double distance = ((Number) distanceObj).doubleValue();
                    totalSimilarity += (1.0 - distance);
                    count++;
                } else if (distanceObj instanceof String) {
                    try {
                        double distance = Double.parseDouble(distanceObj.toString());
                        totalSimilarity += (1.0 - distance);
                        count++;
                    } catch (NumberFormatException e) {
                        log.debug("Could not parse distance: {}", distanceObj);
                    }
                }
            }
        }

        return count > 0 ? totalSimilarity / count : 0.5;
    }

    private boolean hasRelevantMetadata(AssembledContext context) {
        List<Document> allDocs = new java.util.ArrayList<>();
        if (context.getKnowledgeBaseDocuments() != null) {
            allDocs.addAll(context.getKnowledgeBaseDocuments());
        }

        return allDocs.stream()
                .anyMatch(doc -> doc.getMetadata() != null &&
                        (doc.getMetadata().containsKey("documentType") ||
                                doc.getMetadata().containsKey("clientName") ||
                                doc.getMetadata().containsKey("sector")));
    }

    private double calculateCompletenessScore(AssembledContext context, String userQuery) {
        // Completeness = how well the documents cover the query
        // Check if key terms from query are present in documents

        List<String> queryTerms = extractKeyTerms(userQuery);
        double matchedTerms = 0.0;

        List<Document> allDocs = new java.util.ArrayList<>();
        if (context.getKnowledgeBaseDocuments() != null) {
            allDocs.addAll(context.getKnowledgeBaseDocuments());
        }

        String allDocText = allDocs.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + " " + b)
                .toLowerCase();

        for (String term : queryTerms) {
            if (allDocText.contains(term.toLowerCase())) {
                matchedTerms++;
            }
        }

        if (queryTerms.isEmpty()) {
            return 1.0;
        }

        return matchedTerms / queryTerms.size();
    }

    private double calculateFreshnessScore(AssembledContext context) {
        // Freshness = how recent the documents are
        // For now, assuming all documents are recent = 1.0
        // In real scenario, check document date metadata

        return 1.0;
    }

    private double calculateRelevanceScore(AssembledContext context, String userQuery) {
        // Relevance = combination of document count and metadata matching

        int docCount = getTotalDocuments(context);
        boolean hasMetadata = hasRelevantMetadata(context);

        double relevance = Math.min(docCount / 10.0, 1.0);  // Max 1.0 at 10 docs
        if (hasMetadata) {
            relevance += 0.1;
        }

        return Math.min(relevance, 1.0);
    }

    private List<String> extractKeyTerms(String query) {
        // Extract important terms from query (simple implementation)
        return java.util.Arrays.stream(query.split("\\s+"))
                .filter(term -> term.length() > 3)  // Only terms > 3 chars
                .map(String::toLowerCase)
                .distinct()
                .toList();
    }

    private String generateQualitySummary(ContextQualityMetrics metrics) {
        double overallScore = metrics.getOverallScore();

        if (overallScore >= 0.8) {
            return "Excellent context quality - All indicators strong";
        } else if (overallScore >= 0.6) {
            return "Good context quality - Suitable for generation";
        } else if (overallScore >= 0.4) {
            return "Moderate context quality - Some gaps expected";
        } else {
            return "Poor context quality - High hallucination risk";
        }
    }
}
