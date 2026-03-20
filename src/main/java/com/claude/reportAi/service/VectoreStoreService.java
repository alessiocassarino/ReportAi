package com.claude.reportAi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class VectoreStoreService {

    private static final int TOP_K = 5;
    private static final double SIMILARITY_THRESHOLD = 0.4;

    private final VectorStore vectorStore;

    public List<Document> searchRelevantDocuments(String query) {
        log.info("Avvio ricerca nel Vector Store");
        log.info("Query ricevuta='{}'", safe(query));

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .build();

        log.info("Parametri ricerca Vector Store -> topK={}, similarityThreshold={}",
                TOP_K,
                SIMILARITY_THRESHOLD);

        List<Document> results = vectorStore.similaritySearch(request);
        int resultCount = results != null ? results.size() : 0;

        log.info("Ricerca Vector Store completata -> numero documenti trovati={}", resultCount);

        if (results == null || results.isEmpty()) {
            log.warn("Nessun documento trovato nel Vector Store per la query='{}'", safe(query));
            return results;
        }

        logRetrievedDocuments(results);
        return results;
    }

    private void logRetrievedDocuments(List<Document> results) {
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            String textPreview = doc != null ? safe(doc.getText()) : null;
            Map<String, Object> metadata = doc != null ? doc.getMetadata() : null;

            log.info("Documento [{}] trovato -> preview='{}'", i + 1, textPreview);
            log.info("Documento [{}] metadata -> {}", i + 1, metadata);
        }
    }

    private String safe(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > 250 ? normalized.substring(0, 250) + "..." : normalized;
    }
}