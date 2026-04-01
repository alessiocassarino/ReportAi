package com.claude.reportAi.service;

import com.claude.reportAi.dto.DocumentUploadResponse;
import com.claude.reportAi.entities.UploadJob;
import com.claude.reportAi.repository.UploadJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class StoredFileIngestionProcessor {

    private final StoredFileService storedFileService;
    private final UploadJobRepository uploadJobRepository;

    @Async("documentUploadExecutor")
    public void processAsync(UUID jobId, byte[] bytes, String originalFilename, String contentType) {
        log.info("START ingestione | jobId={} | file={} | size={} bytes", jobId, originalFilename, bytes.length);

        UploadJob job = loadJob(jobId);
        job.setStatus(UploadJob.JobStatus.PROCESSING);
        uploadJobRepository.save(job);

        try {
            DocumentUploadResponse result = storedFileService.ingest(bytes, originalFilename, contentType);

            job = loadJob(jobId);
            job.setStoredFileId(result.getId());
            job.setStatus(mapStatus(result.getStatus()));
            uploadJobRepository.save(job);

            log.info("END ingestione | jobId={} | status={} | storedFileId={}",
                    jobId, job.getStatus(), result.getId());

        } catch (Exception e) {
            log.error("ERRORE ingestione | jobId={} | file={}", jobId, originalFilename, e);
            job = loadJob(jobId);
            job.setStatus(UploadJob.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            uploadJobRepository.save(job);
        }
    }

    private UploadJob.JobStatus mapStatus(String serviceStatus) {
        return switch (serviceStatus) {
            case "ALREADY_EXISTS" -> UploadJob.JobStatus.ALREADY_EXISTS;
            case "NO_TEXT"        -> UploadJob.JobStatus.NO_TEXT;
            default               -> UploadJob.JobStatus.COMPLETED;
        };
    }

    private UploadJob loadJob(UUID jobId) {
        return uploadJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job non trovato: " + jobId));
    }
}
