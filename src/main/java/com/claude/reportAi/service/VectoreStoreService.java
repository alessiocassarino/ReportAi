package com.claude.reportAi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class VectoreStoreService {

    private static final int DEFAULT_TOP_K = 10;
    private static final double DEFAULT_SIMILARITY_THRESHOLD =  0.45d;

    private final VectorStore vectorStore;

    public VectoreStoreService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<Document> searchRelevantDocuments(String query) {
        return searchRelevantDocuments(query, null, DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
    }

    public List<Document> searchRelevantDocuments(String query,
                                                  String filterExpression,
                                                  Integer topK,
                                                  Double similarityThreshold) {
        log.info("Avvio ricerca nel Vector Store");
        log.info("Query='{}', filter='{}', topK={}, similarityThreshold={}",
                safe(query),
                filterExpression,
                topK,
                similarityThreshold);

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK != null ? topK : DEFAULT_TOP_K)
                .similarityThreshold(similarityThreshold != null ? similarityThreshold : DEFAULT_SIMILARITY_THRESHOLD);

        if (filterExpression != null && !filterExpression.isBlank()) {
            builder.filterExpression(filterExpression);
        }

        List<Document> results = vectorStore.similaritySearch(builder.build());

        int resultCount = results != null ? results.size() : 0;
        log.info("Ricerca Vector Store completata -> documenti trovati={}", resultCount);

        if (results == null || results.isEmpty()) {
            log.warn("Nessun documento trovato nel Vector Store per query='{}'", safe(query));
            return List.of();
        }

        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            String preview = doc != null ? safe(doc.getText()) : null;
            Map<String, Object> metadata = doc != null ? doc.getMetadata() : null;
            log.info("Documento [{}] preview='{}'", i + 1, preview);
            log.info("Documento [{}] metadata={}", i + 1, metadata);
        }

        return results;
    }

    private String safe(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > 250 ? normalized.substring(0, 250) + "..." : normalized;
    }
}