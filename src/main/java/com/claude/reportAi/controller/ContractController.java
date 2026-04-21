package com.claude.reportAi.controller;

import com.claude.reportAi.entities.ContractAnalysis;
import com.claude.reportAi.service.ContractAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * REST API for asynchronous contract risk analysis.
 *
 * Flow:
 *   0. GET  /api/contracts/models            → lista modelli disponibili
 *   1. POST /api/contracts/analyze            → upload PDF + model, receive jobId
 *   2. GET  /api/contracts/{jobId}/status    → poll until COMPLETED or FAILED
 *   3. GET  /api/contracts/{jobId}/result    → download the generated DOCX
 */
@RestController
@RequestMapping("/api/contracts")
@RequiredArgsConstructor
@Slf4j
public class ContractController {

    private final ContractAnalysisService contractAnalysisService;

    /**
     * Accepts a PDF contract and starts the async risk analysis.
     * Returns the jobId to use for status polling.
     *
     * @param model  ID del modello da usare (default: claude-sonnet-4-5)
     */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'ANALYST')")
    public ResponseEntity<StartJobResponse> analyze(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "model", defaultValue = "claude-haiku-4-5-20251001") String model,
            @RequestParam(value = "outputFileName", required = false) String outputFileName)
            throws IOException {

        UUID jobId = contractAnalysisService.startAnalysis(file, model, outputFileName);
        log.info("Job avviato: {} | model={} | outputFileName={}", jobId, model, outputFileName);

        return ResponseEntity.accepted().body(new StartJobResponse(
                jobId.toString(),
                ContractAnalysis.JobStatus.PENDING.name(),
                model,
                "Analisi avviata. Usa /api/contracts/" + jobId + "/status per monitorare lo stato."
        ));
    }

    /**
     * Returns the current status and progress of a job.
     */
    @GetMapping("/{jobId}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'ANALYST')")
    public ResponseEntity<JobStatusResponse> status(@PathVariable UUID jobId) {
        ContractAnalysis job = contractAnalysisService.getJob(jobId);

        return ResponseEntity.ok(new JobStatusResponse(
                job.getId().toString(),
                job.getStatus().name(),
                job.getProgress(),
                job.getCurrentStep(),
                job.getModel(),
                job.getErrorMessage(),
                job.getResultFileName(),
                job.getStatus() == ContractAnalysis.JobStatus.COMPLETED
                        ? "/api/contracts/" + jobId + "/result"
                        : null
        ));
    }

    @PostMapping("/{jobId}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'ANALYST')")
    public ResponseEntity<Void> cancel(@PathVariable UUID jobId) {
        contractAnalysisService.cancelJob(jobId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Downloads the generated DOCX report for a completed job.
     */
    @GetMapping("/{jobId}/result")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'ANALYST')")
    public ResponseEntity<byte[]> result(@PathVariable UUID jobId) {
        ContractAnalysis job = contractAnalysisService.getJob(jobId);
        byte[] content = contractAnalysisService.getResult(jobId);

        String filename = job.getResultFileName() != null
                ? job.getResultFileName()
                : "contract-risk-analysis.docx";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .contentLength(content.length)
                .body(content);
    }

    // -----------------------------------------------------------------------
    // DTOs
    // -----------------------------------------------------------------------

    record StartJobResponse(String jobId, String status, String model, String message) {}

    record JobStatusResponse(
            String jobId,
            String status,
            int progress,
            String currentStep,
            String model,
            String errorMessage,
            String resultFileName,
            String downloadUrl
    ) {}
}
