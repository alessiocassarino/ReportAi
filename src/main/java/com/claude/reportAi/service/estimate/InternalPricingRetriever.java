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

    // ── RENEWABLE_ENERGY query sets ────────────────────────────────────────

    private static final List<String> RENEWABLE_COVERAGE_SWEEP_QUERIES = List.of(
            "prezzi costi fotovoltaico eolico BESS rinnovabili costruzione",
            "moduli inverter tracker BOS balance of system costi",
            "opere civili connessione rete cabina stazione AT costi"
    );

    private static final List<String> SOLAR_QUERIES = List.of(
            "costi moduli fotovoltaici EUR MWp installazione",
            "prezzi inverter string central inverter fotovoltaico",
            "strutture tracker sistemi di montaggio fotovoltaico costi",
            "opere civili fondazioni pali fotovoltaico",
            "cavi BOS balance of system fotovoltaico costi"
    );

    private static final List<String> WIND_QUERIES = List.of(
            "costi turbine eoliche EUR MW installazione",
            "fondazioni torre eolica opere civili costi",
            "cavidotto interrato eolico connessione rete costi",
            "mobilizzazione cantiere eolico costi personale",
            "commissioning test collaudo eolico costi"
    );

    private static final List<String> BESS_QUERIES = List.of(
            "costi sistemi accumulo BESS batterie EUR MWh",
            "inverter PCS power conversion system BESS costi",
            "BMS battery management system EMS energy management",
            "opere civili fondazioni container BESS costi",
            "connessione rete stazione AT BESS costi"
    );

    public String retrieveContext(ProjectInfoExtractor.ProjectInfo info, String sector) {
        List<String> queries = buildQueries(info, sector);
        List<String> sweepQueries = buildSweepQueries(sector);
        String filterExpr = buildFilterExpression(sector);

        Set<String> seenIds = new HashSet<>();
        Set<String> coveredFileIds = new HashSet<>();
        List<Document> collected = new ArrayList<>();

        // Fase 1: ricerca semantica context-aware, filtrata per settore
        for (String query : queries) {
            if (collected.size() >= MAX_DOCS) break;
            try {
                SearchRequest.Builder reqBuilder = SearchRequest.builder()
                        .query(query)
                        .topK(TOP_K)
                        .similarityThreshold(SIMILARITY_THRESHOLD);
                if (filterExpr != null) reqBuilder.filterExpression(filterExpr);

                List<Document> results = vectorStore.similaritySearch(reqBuilder.build());
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

        // Fase 2: sweep totale per raccogliere chunk non catturati dalla ricerca semantica,
        // sempre filtrato per settore per evitare contaminazione cross-sector.
        if (collected.size() < MAX_DOCS) {
            for (String query : sweepQueries) {
                if (collected.size() >= MAX_DOCS) break;
                try {
                    SearchRequest.Builder reqBuilder = SearchRequest.builder()
                            .query(query)
                            .topK(TOP_K_SWEEP)
                            .similarityThreshold(SIMILARITY_THRESHOLD_SWEEP);
                    if (filterExpr != null) reqBuilder.filterExpression(filterExpr);

                    List<Document> sweepResults = vectorStore.similaritySearch(reqBuilder.build());
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
            log.warn("Vector store vuoto o nessun dato pertinente trovato per sector={}", sector);
            return "";
        }

        log.info("RAG coverage: {} chunk raccolti da {} file distinti (sector={})",
                collected.size(), coveredFileIds.size(), sector);
        return contextFormatter.format(collected);
    }

    private String buildFilterExpression(String sector) {
        if (sector == null || sector.isBlank()) return null;
        // Match either the requested sector or documents tagged as GENERIC (cross-sector)
        return "sector == '" + sector + "' || sector == 'GENERIC'";
    }

    private List<String> buildSweepQueries(String sector) {
        if ("RENEWABLE_ENERGY".equals(sector)) return RENEWABLE_COVERAGE_SWEEP_QUERIES;
        return COVERAGE_SWEEP_QUERIES;
    }

    private List<String> buildQueries(ProjectInfoExtractor.ProjectInfo info, String sector) {
        if ("RENEWABLE_ENERGY".equals(sector)) {
            String tipo = info.tipoProgetto() != null ? info.tipoProgetto().toUpperCase() : "";
            if (tipo.contains("EOLICO") || tipo.contains("WIND")) return WIND_QUERIES;
            if (tipo.contains("BESS") || tipo.contains("ACCUMULO") || tipo.contains("STORAGE")) return BESS_QUERIES;
            if (tipo.contains("FV") || tipo.contains("FOTOVOLTAICO") || tipo.contains("SOLAR") || tipo.contains("PV")) return SOLAR_QUERIES;
            // Default for renewable: combine solar + wind (most common)
            List<String> combined = new ArrayList<>(SOLAR_QUERIES);
            combined.addAll(WIND_QUERIES);
            return combined;
        }

        // OIL_GAS (default)
        String tipo = info.tipoProgetto() != null ? info.tipoProgetto().toUpperCase() : "";
        if ("IMPIANTO".equals(tipo)) return IMPIANTO_QUERIES;
        if ("PIPELINE".equals(tipo)) return PIPELINE_QUERIES;
        List<String> combined = new ArrayList<>(PIPELINE_QUERIES);
        combined.addAll(IMPIANTO_QUERIES);
        return combined;
    }

    private String getFileId(Document doc) {
        if (doc.getMetadata() == null) return null;
        Object fileId = doc.getMetadata().get("fileId");
        return fileId != null ? fileId.toString() : null;
    }
}
