package com.claude.reportAi.pipeline;

import com.claude.reportAi.entities.ContractAnalysis;
import com.claude.reportAi.entities.Estimate;
import com.claude.reportAi.entities.Job;
import com.claude.reportAi.entities.PriceComparison;
import com.claude.reportAi.repository.ContractAnalysisRepository;
import com.claude.reportAi.repository.EstimateRepository;
import com.claude.reportAi.repository.JobRepository;
import com.claude.reportAi.repository.PriceComparisonRepository;
import com.claude.reportAi.sector.SectorProfileRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central orchestrator for v2 pipeline execution.
 *
 * Responsibilities:
 *  - Validates workflowId, sector, and file count against WorkflowRegistry / SectorProfileRegistry
 *  - Creates a unified Job record before dispatching
 *  - Dispatches to the correct PipelineStep (which delegates to existing v1 services)
 *  - Stores the legacyJobId for status/result delegation
 *  - On status/result reads: mirrors the state of the underlying legacy entity
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineEngine {

    private final WorkflowRegistry workflowRegistry;
    private final SectorProfileRegistry sectorProfileRegistry;
    private final JobRepository jobRepository;

    private final ContractAnalysisRepository contractAnalysisRepository;
    private final EstimateRepository estimateRepository;
    private final PriceComparisonRepository priceComparisonRepository;

    private final List<PipelineStep> steps;
    private Map<String, PipelineStep> stepMap;

    @PostConstruct
    void init() {
        stepMap = steps.stream()
                .collect(Collectors.toMap(PipelineStep::stepId, Function.identity()));
        log.info("PipelineEngine inizializzato con {} step: {}", stepMap.size(), stepMap.keySet());
    }

    // -------------------------------------------------------------------------
    // Submit
    // -------------------------------------------------------------------------

    public UUID submit(String workflowId, String sector, String model,
                       List<byte[]> fileContents, List<String> filenames, UUID userId) {

        WorkflowDefinition wf = workflowRegistry.getOrThrow(workflowId);

        if (!wf.supportsSector(sector)) {
            throw new IllegalArgumentException(
                    "Il workflow '" + workflowId + "' non supporta il settore '" + sector + "'.");
        }
        if (!wf.acceptsFileCount(fileContents.size())) {
            throw new IllegalArgumentException(
                    "Il workflow '" + workflowId + "' richiede tra " + wf.minFiles()
                    + " e " + wf.maxFiles() + " file. Forniti: " + fileContents.size());
        }

        Job job = new Job();
        job.setWorkflowId(workflowId);
        job.setSector(sector);
        job.setModel(model);
        job.setNumFiles(fileContents.size());
        job.setInputFilenames(String.join(", ", filenames));
        job.setCreatedBy(userId);
        job.setStatus(Job.JobStatus.PENDING);
        job = jobRepository.save(job);

        UUID jobId = job.getId();
        log.info("Job v2 creato | jobId={} | workflow={} | sector={} | model={} | files={}",
                jobId, workflowId, sector, model, fileContents.size());

        PipelineContext ctx = PipelineContext.of(
                jobId, workflowId, sector, model, fileContents, filenames, userId);

        String stepId = wf.stepIds().getFirst();
        PipelineStep step = stepMap.get(stepId);
        if (step == null) {
            throw new IllegalStateException("Step non registrato: " + stepId);
        }

        try {
            String legacyJobId = step.execute(ctx);

            Job saved = jobRepository.findById(jobId).orElseThrow();
            saved.setLegacyJobId(legacyJobId);
            saved.setLegacyType(resolveLegacyType(workflowId));
            saved.setStatus(Job.JobStatus.PROCESSING);
            jobRepository.save(saved);

        } catch (Exception e) {
            log.error("Step '{}' fallito per jobId={}: {}", stepId, jobId, e.getMessage(), e);
            Job failed = jobRepository.findById(jobId).orElseThrow();
            failed.setStatus(Job.JobStatus.FAILED);
            failed.setErrorMessage(e.getMessage());
            jobRepository.save(failed);
        }

        return jobId;
    }

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    public Job getStatus(UUID jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job non trovato: " + jobId));

        if (job.getLegacyJobId() != null) {
            syncStatusFromLegacy(job);
        }

        return job;
    }

    // -------------------------------------------------------------------------
    // Result
    // -------------------------------------------------------------------------

    public byte[] getResult(UUID jobId) {
        Job job = getStatus(jobId);

        if (job.getStatus() != Job.JobStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Il job non è ancora completato. Stato: " + job.getStatus());
        }

        UUID legacyId = UUID.fromString(job.getLegacyJobId());

        return switch (job.getLegacyType()) {
            case "CONTRACT" -> contractAnalysisRepository.findById(legacyId)
                    .map(ContractAnalysis::getResultFileContent)
                    .orElseThrow(() -> new IllegalStateException("Risultato non disponibile."));
            case "ESTIMATE" -> estimateRepository.findById(legacyId)
                    .map(Estimate::getResultFileContent)
                    .orElseThrow(() -> new IllegalStateException("Risultato non disponibile."));
            case "PRICE_COMPARISON" -> priceComparisonRepository.findById(legacyId)
                    .map(PriceComparison::getResultFileContent)
                    .orElseThrow(() -> new IllegalStateException("Risultato non disponibile."));
            default -> throw new IllegalStateException("Tipo legacy non supportato: " + job.getLegacyType());
        };
    }

    // -------------------------------------------------------------------------
    // Cancel
    // -------------------------------------------------------------------------

    public void cancel(UUID jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job non trovato: " + jobId));

        if (job.getStatus() == Job.JobStatus.COMPLETED
                || job.getStatus() == Job.JobStatus.FAILED
                || job.getStatus() == Job.JobStatus.CANCELLED) {
            throw new IllegalStateException("Impossibile annullare un job già terminato.");
        }

        if (job.getLegacyJobId() != null) {
            UUID legacyId = UUID.fromString(job.getLegacyJobId());
            cancelLegacy(job.getLegacyType(), legacyId);
        }

        job.setStatus(Job.JobStatus.CANCELLED);
        job.setCurrentStep("Annullato dall'utente");
        jobRepository.save(job);
        log.info("Job v2 annullato | jobId={}", jobId);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void syncStatusFromLegacy(Job job) {
        UUID legacyId = UUID.fromString(job.getLegacyJobId());
        Job.JobStatus mapped = switch (job.getLegacyType()) {
            case "CONTRACT" -> contractAnalysisRepository.findById(legacyId)
                    .map(e -> mapStatus(e.getStatus().name()))
                    .orElse(job.getStatus());
            case "ESTIMATE" -> estimateRepository.findById(legacyId)
                    .map(e -> mapStatus(e.getStatus().name()))
                    .orElse(job.getStatus());
            case "PRICE_COMPARISON" -> priceComparisonRepository.findById(legacyId)
                    .map(e -> mapStatus(e.getStatus().name()))
                    .orElse(job.getStatus());
            default -> job.getStatus();
        };

        if (mapped != job.getStatus()) {
            job.setStatus(mapped);
            updateProgressFromLegacy(job, legacyId);
            jobRepository.save(job);
        }
    }

    private void updateProgressFromLegacy(Job job, UUID legacyId) {
        switch (job.getLegacyType()) {
            case "CONTRACT" -> contractAnalysisRepository.findById(legacyId).ifPresent(e -> {
                job.setProgress(e.getProgress());
                job.setCurrentStep(e.getCurrentStep());
                job.setErrorMessage(e.getErrorMessage());
            });
            case "ESTIMATE" -> estimateRepository.findById(legacyId).ifPresent(e -> {
                job.setProgress(e.getProgress());
                job.setCurrentStep(e.getCurrentStep());
                job.setErrorMessage(e.getErrorMessage());
            });
            case "PRICE_COMPARISON" -> priceComparisonRepository.findById(legacyId).ifPresent(e -> {
                job.setProgress(e.getProgress());
                job.setCurrentStep(e.getCurrentStep());
                job.setErrorMessage(e.getErrorMessage());
            });
        }
    }

    private void cancelLegacy(String legacyType, UUID legacyId) {
        switch (legacyType) {
            case "CONTRACT" -> contractAnalysisRepository.findById(legacyId).ifPresent(e -> {
                e.setStatus(ContractAnalysis.JobStatus.CANCELLED);
                e.setCurrentStep("Annullato via v2 API");
                contractAnalysisRepository.save(e);
            });
            case "ESTIMATE" -> estimateRepository.findById(legacyId).ifPresent(e -> {
                e.setStatus(Estimate.JobStatus.CANCELLED);
                e.setCurrentStep("Annullato via v2 API");
                estimateRepository.save(e);
            });
            case "PRICE_COMPARISON" -> priceComparisonRepository.findById(legacyId).ifPresent(e -> {
                e.setStatus(PriceComparison.JobStatus.CANCELLED);
                e.setCurrentStep("Annullato via v2 API");
                priceComparisonRepository.save(e);
            });
        }
    }

    private Job.JobStatus mapStatus(String rawStatus) {
        return switch (rawStatus) {
            case "PENDING"    -> Job.JobStatus.PENDING;
            case "PROCESSING" -> Job.JobStatus.PROCESSING;
            case "COMPLETED"  -> Job.JobStatus.COMPLETED;
            case "FAILED"     -> Job.JobStatus.FAILED;
            case "CANCELLED"  -> Job.JobStatus.CANCELLED;
            default           -> Job.JobStatus.PROCESSING;
        };
    }

    private String resolveLegacyType(String workflowId) {
        return switch (workflowId) {
            case "contract-risk-analysis" -> "CONTRACT";
            case "estimate-generation"    -> "ESTIMATE";
            case "price-comparison"       -> "PRICE_COMPARISON";
            default -> throw new IllegalArgumentException("Workflow senza mapping legacy: " + workflowId);
        };
    }
}
