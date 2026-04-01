package com.claude.reportAi.service.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class InternalPricingRetriever {

    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

    private static final int TOP_K = 5;
    private static final double SIMILARITY_THRESHOLD = 0.4;
    private static final int MAX_DOCS = 20;

    private static final List<String> PIPELINE_QUERIES = List.of(
            "costi spread pipeline saldatura posa",
            "prezzi personale operatori pipeline",
            "costi mezzi side boom pay welder pipeline",
            "mobilizzazione attrezzatura costi",
            "carburante consumabili pipeline cantiere"
    );

    private static final List<String> IMPIANTO_QUERIES = List.of(
            "costi montaggio impianto piping",
            "prezzi personale impianti oil gas",
            "costi attrezzatura impianti",
            "mobilizzazione impianti",
            "carburante consumabili impianti"
    );

    public String retrieveContext(ProjectInfoExtractor.ProjectInfo info) {
        List<String> queries = buildQueries(info);
        Set<String> seenIds = new HashSet<>();
        List<Document> collected = new ArrayList<>();

        for (String query : queries) {
            if (collected.size() >= MAX_DOCS) break;
            try {
                List<Document> results = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(query)
                                .topK(TOP_K)
                                .similarityThreshold(SIMILARITY_THRESHOLD)
                                .build()
                );
                if (results != null) {
                    for (Document doc : results) {
                        if (collected.size() >= MAX_DOCS) break;
                        String docId = doc.getId();
                        if (docId == null || seenIds.add(docId)) {
                            collected.add(doc);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Errore durante similarity search per query '{}': {}", query, e.getMessage());
            }
        }

        if (collected.isEmpty()) {
            log.warn("Vector store vuoto o nessun dato pertinente trovato");
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Document doc : collected) {
            String filename = getFilename(doc);
            sb.append("=== ").append(filename).append(" ===\n");
            sb.append(doc.getText()).append("\n\n");
        }
        return sb.toString();
    }

    private List<String> buildQueries(ProjectInfoExtractor.ProjectInfo info) {
        String tipo = info.tipoProgetto() != null ? info.tipoProgetto().toUpperCase() : "";
        if ("IMPIANTO".equals(tipo)) {
            return IMPIANTO_QUERIES;
        } else if ("PIPELINE".equals(tipo)) {
            return PIPELINE_QUERIES;
        } else {
            // MISTO o non specificato: unione di entrambe
            List<String> combined = new ArrayList<>(PIPELINE_QUERIES);
            combined.addAll(IMPIANTO_QUERIES);
            return combined;
        }
    }

    private String getFilename(Document doc) {
        if (doc.getMetadata() == null) return "documento";
        Object filename = doc.getMetadata().get("filename");
        if (filename != null) return filename.toString();
        Object source = doc.getMetadata().get("source");
        if (source != null) return source.toString();
        return "documento";
    }
}
