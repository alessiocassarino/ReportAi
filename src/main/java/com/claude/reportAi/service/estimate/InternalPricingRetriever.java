package com.claude.reportAi.service.estimate;

import com.claude.reportAi.service.rag.VectorStoreContextFormatter;
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
    private final VectorStoreContextFormatter contextFormatter;

    private static final int TOP_K = 12;
    private static final double SIMILARITY_THRESHOLD = 0.4;
    private static final int MAX_DOCS = 400;

    // Fase 2: sweep totale — threshold 0.0 e TOP_K = MAX_DOCS garantiscono
    // che tutti i chunk del vector store vengano raccolti indipendentemente dalla similarità
    private static final int TOP_K_SWEEP = MAX_DOCS;
    private static final double SIMILARITY_THRESHOLD_SWEEP = 0.0;

    private static final List<String> COVERAGE_SWEEP_QUERIES = List.of(
            "prezzi costi rate rese pipeline impianto oil gas costruzione",
            "squadra operatori mezzi personale costi mensili spread",
            "rese produttività saldatura posa cantiere attrezzatura mobilizzo"
    );

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
        Set<String> coveredFileIds = new HashSet<>();
        List<Document> collected = new ArrayList<>();

        // Fase 1: ricerca semantica context-aware
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
                            String fileId = getFileId(doc);
                            if (fileId != null) coveredFileIds.add(fileId);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Errore durante similarity search per query '{}': {}", query, e.getMessage());
            }
        }

        // Fase 2: sweep totale per raccogliere i chunk non catturati dalla ricerca semantica.
        // Usa threshold=0.0 e TOP_K=MAX_DOCS per pescare tutti i documenti del vector store;
        // la deduplicazione su seenIds evita duplicati già raccolti in Fase 1.
        if (collected.size() < MAX_DOCS) {
            for (String query : COVERAGE_SWEEP_QUERIES) {
                if (collected.size() >= MAX_DOCS) break;
                try {
                    List<Document> sweepResults = vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query(query)
                                    .topK(TOP_K_SWEEP)
                                    .similarityThreshold(SIMILARITY_THRESHOLD_SWEEP)
                                    .build()
                    );
                    for (Document doc : sweepResults) {
                        if (collected.size() >= MAX_DOCS) break;
                        String docId = doc.getId();
                        if (seenIds.add(docId)) {
                            collected.add(doc);
                            String fileId = getFileId(doc);
                            if (fileId != null) coveredFileIds.add(fileId);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Errore durante sweep query '{}': {}", query, e.getMessage());
                }
            }
        }

        if (collected.isEmpty()) {
            log.warn("Vector store vuoto o nessun dato pertinente trovato");
            return "";
        }

        log.info("RAG coverage: {} chunk raccolti da {} file distinti", collected.size(), coveredFileIds.size());
        return contextFormatter.format(collected);
    }

    private String getFileId(Document doc) {
        if (doc.getMetadata() == null) return null;
        Object fileId = doc.getMetadata().get("fileId");
        return fileId != null ? fileId.toString() : null;
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

}
