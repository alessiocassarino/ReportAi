package com.claude.reportAi.controller;

import com.claude.reportAi.entities.UploadJob;
import com.claude.reportAi.repository.UploadJobRepository;
import com.claude.reportAi.service.StoredFileIngestionProcessor;
import com.claude.reportAi.service.StoredFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * REST API for asynchronous document ingestion into the vector store.
 *
 * Flow:
 *   1. POST /api/documents/upload        → upload one or more files, receive jobIds (202 Accepted)
 *   2. GET  /api/documents/{jobId}/status → poll until COMPLETED, ALREADY_EXISTS, NO_TEXT or FAILED
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Slf4j
public class StoredFileController {

    private final StoredFileIngestionProcessor ingestionProcessor;
    private final UploadJobRepository uploadJobRepository;

    /**
     * Accepts one or more files and starts async ingestion for each.
     * Returns 202 Accepted immediately with the list of created job IDs.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<StartUploadResponse>> upload(
            @RequestPart("files") List<MultipartFile> files) throws IOException {

        List<StartUploadResponse> responses = new ArrayList<>();

        for (MultipartFile file : files) {
            String originalFilename = file.getOriginalFilename() != null
                    ? file.getOriginalFilename()
                    : "file.bin";
            String contentType = file.getContentType();

            // Validate synchronously so the caller gets a 400 before any job is created
            byte[] bytes = file.getBytes();
            StoredFileService.validateFile(bytes, originalFilename, contentType);

            UploadJob job = new UploadJob();
            job.setOriginalFilename(originalFilename);
            uploadJobRepository.save(job);

            ingestionProcessor.processAsync(job.getId(), bytes, originalFilename, contentType);

            log.info("Job upload creato: {} | file={} | size={} bytes", job.getId(), originalFilename, bytes.length);

            responses.add(new StartUploadResponse(
                    job.getId().toString(),
                    originalFilename,
                    UploadJob.JobStatus.PENDING.name(),
                    "Indicizzazione avviata. Usa /api/documents/" + job.getId() + "/status per monitorare."
            ));
        }

        return ResponseEntity.accepted().body(responses);
    }

    /**
     * Returns the current status of an ingestion job.
     */
    @GetMapping("/{jobId}/status")
    public ResponseEntity<UploadJobStatusResponse> status(@PathVariable UUID jobId) {
        UploadJob job = uploadJobRepository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("Job non trovato: " + jobId));

        return ResponseEntity.ok(new UploadJobStatusResponse(
                job.getId().toString(),
                job.getOriginalFilename(),
                job.getStatus().name(),
                job.getStoredFileId(),
                job.getErrorMessage(),
                job.getCreatedAt() != null ? job.getCreatedAt().toString() : null
        ));
    }

    // -----------------------------------------------------------------------
    // DTOs
    // -----------------------------------------------------------------------

    record StartUploadResponse(String jobId, String filename, String status, String message) {}

    record UploadJobStatusResponse(
            String jobId,
            String originalFilename,
            String status,
            UUID storedFileId,
            String errorMessage,
            String createdAt
    ) {}
}
