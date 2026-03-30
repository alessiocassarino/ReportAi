package com.claude.reportAi.service;

import com.claude.reportAi.entities.ContractAnalysisJob;
import com.claude.reportAi.repository.ContractAnalysisJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContractAnalysisService {

    private final ContractAnalysisJobRepository jobRepository;
    private final ContractAnalysisProcessor processor;

    public UUID startAnalysis(MultipartFile file) throws IOException {
        validateFile(file);

        ContractAnalysisJob job = new ContractAnalysisJob();
        job = jobRepository.save(job);
        UUID jobId = job.getId();

        byte[] pdfBytes = file.getBytes();
        String originalFilename = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "contratto.pdf";

        log.info("Job creato: {} | file={} | size={} bytes", jobId, originalFilename, pdfBytes.length);

        processor.processAsync(jobId, pdfBytes, originalFilename);

        return jobId;
    }

    public ContractAnalysisJob getJob(UUID jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job non trovato: " + jobId));
    }

    public byte[] getResult(UUID jobId) {
        ContractAnalysisJob job = getJob(jobId);

        if (job.getStatus() != ContractAnalysisJob.JobStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Il job non è ancora completato. Stato attuale: " + job.getStatus());
        }

        if (job.getResultFileContent() == null) {
            throw new IllegalStateException("Il file risultante non è disponibile.");
        }

        return job.getResultFileContent();
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File PDF obbligatorio");
        }

        String contentType = file.getContentType();
        if (!"application/pdf".equals(contentType)) {
            throw new IllegalArgumentException(
                    "Solo file PDF accettati. Ricevuto: " + contentType);
        }
    }
}
