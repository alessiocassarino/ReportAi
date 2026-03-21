package com.claude.reportAi.service;

import com.claude.reportAi.constant.DocumentType;
import com.claude.reportAi.dto.QueryMetadataHints;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class VectoreStoreService {

    private static final int TOP_K = 12;
    private static final double SIMILARITY_THRESHOLD = 0.4;
    private static final int FINAL_RESULTS_LIMIT = 5;

    private final VectorStore vectorStore;
    private final QueryMetadataService queryMetadataService;

    public List<Document> searchRelevantDocuments(String query) {
        log.info("Avvio ricerca nel Vector Store");
        log.info("Query ricevuta='{}'", safe(query));

        QueryMetadataHints hints = queryMetadataService.extractHints(query);

        log.info("Hints estratti -> docTypes={}, countries={}, clients={}, sectors={}, contractTypes={}",
                hints.getPreferredDocumentTypes(),
                hints.getCountries(),
                hints.getClients(),
                hints.getSectors(),
                hints.getContractTypes());

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .build();

        List<Document> rawResults = vectorStore.similaritySearch(request);
        int rawResultCount = rawResults != null ? rawResults.size() : 0;

        log.info("Ricerca Vector Store completata -> numero documenti trovati={}", rawResultCount);

        if (rawResults == null || rawResults.isEmpty()) {
            log.warn("Nessun documento trovato nel Vector Store per la query='{}'", safe(query));
            return List.of();
        }

        List<Document> rankedResults = rerankByMetadata(rawResults, hints).stream()
                .limit(FINAL_RESULTS_LIMIT)
                .toList();

        logRetrievedDocuments(rankedResults);
        return rankedResults;
    }

    private List<Document> rerankByMetadata(List<Document> results, QueryMetadataHints hints) {
        return results.stream()
                .sorted(Comparator.comparingInt((Document doc) -> scoreDocument(doc, hints)).reversed())
                .collect(Collectors.toList());
    }

    private int scoreDocument(Document doc, QueryMetadataHints hints) {
        if (doc == null) {
            return Integer.MIN_VALUE;
        }

        int score = 0;
        Map<String, Object> md = doc.getMetadata() != null ? doc.getMetadata() : Map.of();

        String documentType = getMetadata(md, "documentType");
        String country = getMetadata(md, "country");
        String clientName = getMetadata(md, "clientName");
        String sector = getMetadata(md, "sector");
        String contractType = getMetadata(md, "contractType");
        String tags = getMetadata(md, "tags");
        String text = doc.getText() != null ? doc.getText().toLowerCase(Locale.ROOT) : "";

        if (hints.getPreferredDocumentTypes() != null && !hints.getPreferredDocumentTypes().isEmpty()) {
            for (DocumentType preferredType : hints.getPreferredDocumentTypes()) {
                if (preferredType.name().equalsIgnoreCase(documentType)) {
                    score += 30;
                }
            }
        }

        if (hints.getCountries() != null && !hints.getCountries().isEmpty()) {
            for (String hintCountry : hints.getCountries()) {
                if (hintCountry.equalsIgnoreCase(country)) {
                    score += 20;
                }
            }
        }

        if (hints.getClients() != null && !hints.getClients().isEmpty()) {
            for (String hintClient : hints.getClients()) {
                if (containsIgnoreCase(clientName, hintClient) || containsIgnoreCase(tags, hintClient) || containsIgnoreCase(text, hintClient)) {
                    score += 25;
                }
            }
        }

        if (hints.getSectors() != null && !hints.getSectors().isEmpty()) {
            for (String hintSector : hints.getSectors()) {
                if (hintSector.equalsIgnoreCase(sector) || containsIgnoreCase(tags, hintSector)) {
                    score += 15;
                }
            }
        }

        if (hints.getContractTypes() != null && !hints.getContractTypes().isEmpty()) {
            for (String hintContractType : hints.getContractTypes()) {
                if (hintContractType.equalsIgnoreCase(contractType) || containsIgnoreCase(tags, hintContractType)) {
                    score += 15;
                }
            }
        }

        if (hints.getKeywords() != null && !hints.getKeywords().isEmpty()) {
            for (String keyword : hints.getKeywords()) {
                if (containsIgnoreCase(text, keyword) || containsIgnoreCase(tags, keyword)) {
                    score += 2;
                }
            }
        }

        return score;
    }

    private String getMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private boolean containsIgnoreCase(String text, String token) {
        if (text == null || token == null) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
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