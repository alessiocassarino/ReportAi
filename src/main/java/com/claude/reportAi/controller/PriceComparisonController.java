package com.claude.reportAi.controller;

import com.claude.reportAi.entities.PriceComparison;
import com.claude.reportAi.service.pricecomparison.PriceComparisonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * REST API per il confronto comparativo di offerte fornitori (PDF multipli).
 * Accessibile SOLO agli utenti con ruolo ADMIN.
 *
 * Flow:
 *   0. GET  /api/price-comparison/models               → lista modelli disponibili
 *   1. POST /api/price-comparison/compare              → upload multiplo PDF + model → jobId
 *   2. GET  /api/price-comparison/{jobId}/status       → polling stato job
 *   3. GET  /api/price-comparison/{jobId}/result       → download DOCX
 */
@RestController
@RequestMapping("/api/price-comparison")
@RequiredArgsConstructor
@Slf4j
public class PriceComparisonController {

    private final PriceComparisonService service;

    @PostMapping(value = "/compare", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StartJobResponse> compare(
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(value = "model", defaultValue = "claude-haiku-4-5-20251001") String model)
            throws IOException {

        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Fornire almeno 2 file PDF per il confronto.");
        }
        if (files.size() < 2) {
            throw new IllegalArgumentException("Fornire almeno 2 offerte PDF per effettuare il confronto.");
        }

        List<byte[]>  fileContents = new ArrayList<>();
        List<String>  filenames    = new ArrayList<>();

        for (MultipartFile file : files) {
            String contentType = file.getContentType();
            if (contentType == null || !contentType.equals("application/pdf")) {
                throw new IllegalArgumentException(
                        "Tutti i file devono essere PDF. Tipo non valido: "
                        + contentType + " per " + file.getOriginalFilename());
            }
            fileContents.add(file.getBytes());
            filenames.add(file.getOriginalFilename() != null
                    ? file.getOriginalFilename() : "offerta.pdf");
        }

        UUID jobId = service.startComparison(fileContents, filenames, model);
        log.info("PriceComparisonJob avviato: {} | model={} | numFiles={}", jobId, model, files.size());

        return ResponseEntity.accepted().body(new StartJobResponse(
                jobId.toString(),
                PriceComparison.JobStatus.PENDING.name(),
                model,
                files.size() + " offerte ricevute. Confronto avviato. "
                + "Usa /api/price-comparison/" + jobId + "/status per monitorare lo stato."
        ));
    }

    @GetMapping("/{jobId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<JobStatusResponse> status(@PathVariable UUID jobId) {
        PriceComparison job = service.getJob(jobId);

        String downloadUrl = job.getStatus() == PriceComparison.JobStatus.COMPLETED
                ? "/api/price-comparison/" + jobId + "/result"
                : null;

        return ResponseEntity.ok(new JobStatusResponse(
                job.getId().toString(),
                job.getStatus().name(),
                job.getProgress(),
                job.getCurrentStep(),
                job.getModel(),
                job.getNumFiles(),
                job.getOriginalFilenames(),
                job.getErrorMessage(),
                downloadUrl
        ));
    }

    @GetMapping("/{jobId}/result")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> result(@PathVariable UUID jobId) {
        PriceComparison job = service.getJob(jobId);
        byte[] content = service.getResult(jobId);

        String filename = job.getResultFileName() != null
                ? job.getResultFileName()
                : "valutazione-fornitori.docx";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .contentLength(content.length)
                .body(content);
    }

    // ── DTOs ─────────────────────────────────────────────────────────

    record StartJobResponse(String jobId, String status, String model, String message) {}

    record JobStatusResponse(
            String jobId,
            String status,
            int    progress,
            String currentStep,
            String model,
            int    numFiles,
            String filenames,
            String errorMessage,
            String downloadUrl
    ) {}
}
