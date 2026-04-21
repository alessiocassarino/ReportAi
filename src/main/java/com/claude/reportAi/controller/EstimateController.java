package com.claude.reportAi.controller;

import com.claude.reportAi.entities.Estimate;
import com.claude.reportAi.service.estimate.EstimateGenerationService;
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
 * REST API for asynchronous cost estimate (preventivo) generation.
 * <p>
 * Flow:
 *   0. GET  /api/estimates/models               → lista modelli disponibili
 *   1. POST /api/estimates/generate             → upload PDF + model, receive jobId
 *   2. GET  /api/estimates/{jobId}/status       → poll until COMPLETED or FAILED
 *   3. GET  /api/estimates/{jobId}/result       → download the generated DOCX
 */
@RestController
@RequestMapping("/api/estimates")
@RequiredArgsConstructor
@Slf4j
public class EstimateController {

    private final EstimateGenerationService estimateGenerationService;

    @PostMapping(value = "/preventivo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'ANALYST')")
    public ResponseEntity<StartJobResponse> generate(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "model", defaultValue = "claude-haiku-4-5-20251001") String model,
            @RequestParam(value = "outputFileName", required = false) String outputFileName)
            throws IOException {

        String contentType = file.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            throw new IllegalArgumentException(
                    "Il file deve essere un PDF. Tipo ricevuto: " + contentType);
        }

        byte[] pdfBytes = file.getBytes();
        String originalFilename = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "document.pdf";

        UUID jobId = estimateGenerationService.startGeneration(pdfBytes, originalFilename, model, outputFileName);
        log.info("ReportJob avviato: {} | model={} | file={} | outputFileName={}", jobId, model, originalFilename, outputFileName);

        return ResponseEntity.accepted().body(new StartJobResponse(
                jobId.toString(),
                Estimate.JobStatus.PENDING.name(),
                model,
                "Generazione preventivo avviata. Usa /api/estimates/" + jobId + "/status per monitorare lo stato."
        ));
    }

    @GetMapping("/{jobId}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'ANALYST')")
    public ResponseEntity<JobStatusResponse> status(@PathVariable UUID jobId) {
        Estimate job = estimateGenerationService.getJob(jobId);

        String downloadUrl = job.getStatus() == Estimate.JobStatus.COMPLETED
                ? "/api/estimates/" + jobId + "/result"
                : null;

        return ResponseEntity.ok(new JobStatusResponse(
                job.getId().toString(),
                job.getStatus().name(),
                job.getProgress(),
                job.getCurrentStep(),
                job.getModel(),
                job.getErrorMessage(),
                downloadUrl
        ));
    }

    @PostMapping("/{jobId}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'ANALYST')")
    public ResponseEntity<Void> cancel(@PathVariable UUID jobId) {
        estimateGenerationService.cancelJob(jobId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{jobId}/result")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'ANALYST')")
    public ResponseEntity<byte[]> result(@PathVariable UUID jobId) {
        Estimate job = estimateGenerationService.getJob(jobId);
        byte[] content = estimateGenerationService.getResult(jobId);

        String filename = job.getResultFileName() != null
                ? job.getResultFileName()
                : "estimate-report.docx";

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
            String downloadUrl
    ) {}
}
