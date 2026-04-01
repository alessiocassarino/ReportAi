package com.claude.reportAi.controller;

import com.claude.reportAi.entities.ReportJob;
import com.claude.reportAi.service.ModelChatClientFactory;
import com.claude.reportAi.service.report.ReportGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * REST API for asynchronous cost estimate (preventivo) generation.
 *
 * Flow:
 *   0. GET  /api/reports/models               → lista modelli disponibili
 *   1. POST /api/reports/generate             → upload PDF + model, receive jobId
 *   2. GET  /api/reports/{jobId}/status       → poll until COMPLETED or FAILED
 *   3. GET  /api/reports/{jobId}/result       → download the generated DOCX
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Slf4j
public class ReportController {

    private final ReportGenerationService reportGenerationService;

    @GetMapping("/models")
    public ResponseEntity<List<ModelChatClientFactory.ModelInfo>> listModels() {
        return ResponseEntity.ok(ModelChatClientFactory.SUPPORTED_MODELS);
    }

    @PostMapping(value = "/preventivo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StartJobResponse> generate(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "model", defaultValue = "claude-haiku-4-5-20251001") String model)
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

        UUID jobId = reportGenerationService.startGeneration(pdfBytes, originalFilename, model);
        log.info("ReportJob avviato: {} | model={} | file={}", jobId, model, originalFilename);

        return ResponseEntity.accepted().body(new StartJobResponse(
                jobId.toString(),
                ReportJob.JobStatus.PENDING.name(),
                model,
                "Generazione preventivo avviata. Usa /api/reports/" + jobId + "/status per monitorare lo stato."
        ));
    }

    @GetMapping("/{jobId}/status")
    public ResponseEntity<JobStatusResponse> status(@PathVariable UUID jobId) {
        ReportJob job = reportGenerationService.getJob(jobId);

        String downloadUrl = job.getStatus() == ReportJob.JobStatus.COMPLETED
                ? "/api/reports/" + jobId + "/result"
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

    @GetMapping("/{jobId}/result")
    public ResponseEntity<byte[]> result(@PathVariable UUID jobId) {
        ReportJob job = reportGenerationService.getJob(jobId);
        byte[] content = reportGenerationService.getResult(jobId);

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
