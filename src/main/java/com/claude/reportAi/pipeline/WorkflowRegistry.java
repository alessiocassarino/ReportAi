package com.claude.reportAi.pipeline;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * In-memory registry of all available workflow definitions.
 * To add a new workflow: add one entry here + a corresponding PipelineStep implementation.
 */
@Component
public class WorkflowRegistry {

    private static final List<WorkflowDefinition> ALL = List.of(
            new WorkflowDefinition(
                    "contract-risk-analysis",
                    "Analisi Rischi Contratto",
                    "Analizza un contratto PDF identificando rischi, clausole critiche e raccomandazioni per il Contractor.",
                    List.of("OIL_GAS", "RENEWABLE_ENERGY"),
                    List.of("contract-risk-step"),
                    1, 1,
                    "application/pdf"
            ),
            new WorkflowDefinition(
                    "estimate-generation",
                    "Generazione Preventivo",
                    "Genera un preventivo EPC dettagliato da una specifica tecnica PDF, integrando prezzi interni, benchmark e ricerca web.",
                    List.of("OIL_GAS", "RENEWABLE_ENERGY"),
                    List.of("estimate-step"),
                    1, 1,
                    "application/pdf"
            ),
            new WorkflowDefinition(
                    "price-comparison",
                    "Confronto Offerte Fornitori",
                    "Confronta 2-10 offerte PDF di fornitori diversi, produce una valutazione comparativa con raccomandazione.",
                    List.of("OIL_GAS", "RENEWABLE_ENERGY"),
                    List.of("price-comparison-step"),
                    2, 10,
                    "application/pdf"
            )
    );

    private final Map<String, WorkflowDefinition> byId = ALL.stream()
            .collect(Collectors.toMap(WorkflowDefinition::id, Function.identity()));

    public Optional<WorkflowDefinition> find(String workflowId) {
        return Optional.ofNullable(byId.get(workflowId));
    }

    public WorkflowDefinition getOrThrow(String workflowId) {
        return find(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow non supportato: " + workflowId));
    }

    public List<WorkflowDefinition> getAll() {
        return ALL;
    }

    public List<WorkflowDefinition> getForSector(String sector) {
        return ALL.stream()
                .filter(w -> w.supportsSector(sector))
                .collect(Collectors.toList());
    }
}
