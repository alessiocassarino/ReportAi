package com.claude.reportAi.service;

import com.claude.reportAi.dto.VectorUploadResponse;
import com.claude.reportAi.entities.VectoreUpload;
import com.claude.reportAi.exception.JobCancelledException;
import com.claude.reportAi.repository.VectorUploadRepository;
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
    private final VectorUploadRepository vectorUploadRepository;

    @Async("documentUploadExecutor")
    public void processAsync(UUID jobId, byte[] bytes, String originalFilename, String contentType) {
        log.info("START ingestione | jobId={} | file={} | size={} bytes", jobId, originalFilename, bytes.length);

        VectoreUpload job = loadJob(jobId);
        throwIfCancelled(jobId);
        job.setStatus(VectoreUpload.JobStatus.PROCESSING);
        vectorUploadRepository.save(job);

        try {
            VectorUploadResponse result = storedFileService.ingest(bytes, originalFilename, contentType);

            job = loadJob(jobId);
            job.setStoredFileId(result.getId());
            job.setStatus(mapStatus(result.getStatus()));
            vectorUploadRepository.save(job);

            log.info("END ingestione | jobId={} | status={} | storedFileId={}",
                    jobId, job.getStatus(), result.getId());

        } catch (JobCancelledException e) {
            log.info("Job annullato dall'utente | jobId={}", jobId);
        } catch (Exception e) {
            log.error("ERRORE ingestione | jobId={} | file={}", jobId, originalFilename, e);
            job = loadJob(jobId);
            job.setStatus(VectoreUpload.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            vectorUploadRepository.save(job);
        }
    }

    private void throwIfCancelled(UUID jobId) {
        if (loadJob(jobId).getStatus() == VectoreUpload.JobStatus.CANCELLED) {
            throw new JobCancelledException(jobId);
        }
    }

    private VectoreUpload.JobStatus mapStatus(String serviceStatus) {
        return switch (serviceStatus) {
            case "ALREADY_EXISTS" -> VectoreUpload.JobStatus.ALREADY_EXISTS;
            case "NO_TEXT"        -> VectoreUpload.JobStatus.NO_TEXT;
            default               -> VectoreUpload.JobStatus.COMPLETED;
        };
    }

    private VectoreUpload loadJob(UUID jobId) {
        return vectorUploadRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job non trovato: " + jobId));
    }
}
