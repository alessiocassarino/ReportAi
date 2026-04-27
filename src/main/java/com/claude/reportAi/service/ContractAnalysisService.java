package com.claude.reportAi.service;

import com.claude.reportAi.entities.ContractAnalysis;
import com.claude.reportAi.repository.ContractAnalysisRepository;
import com.claude.reportAi.util.FileNameSanitizer;
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

    private final ContractAnalysisRepository contractAnalysisRepository;
    private final ContractAnalysisProcessor processor;

    public UUID startAnalysis(MultipartFile file, String model, String customOutputFileName) throws IOException {
        return startAnalysis(file, model, customOutputFileName, "OIL_GAS");
    }

    public UUID startAnalysis(MultipartFile file, String model, String customOutputFileName, String sector) throws IOException {
        validateFile(file);
        ModelChatClientFactory.findModel(model); // valida che il modello sia supportato

        String originalFilename = file.getOriginalFilename() != null
                ? file.getOriginalFilename()
                : "contratto.pdf";

        ContractAnalysis job = new ContractAnalysis();
        job.setModel(model);
        job.setOriginalFilename(originalFilename);
        job.setSector(sector != null ? sector : "OIL_GAS");

        String sanitizedOutputFileName = FileNameSanitizer.sanitize(customOutputFileName);
        if (sanitizedOutputFileName != null) {
            job.setResultFileName(sanitizedOutputFileName);
        }

        job = contractAnalysisRepository.save(job);
        UUID jobId = job.getId();

        byte[] pdfBytes = file.getBytes();

        log.info("Job creato: {} | file={} | size={} bytes | model={}", jobId, originalFilename, pdfBytes.length, model);

        processor.processAsync(jobId, pdfBytes, originalFilename, model);

        return jobId;
    }

    public ContractAnalysis getJob(UUID jobId) {
        return contractAnalysisRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job non trovato: " + jobId));
    }

    public void cancelJob(UUID jobId) {
        ContractAnalysis job = getJob(jobId);
        if (job.getStatus() == ContractAnalysis.JobStatus.COMPLETED
                || job.getStatus() == ContractAnalysis.JobStatus.FAILED
                || job.getStatus() == ContractAnalysis.JobStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Impossibile annullare un job già terminato. Stato: " + job.getStatus());
        }
        job.setStatus(ContractAnalysis.JobStatus.CANCELLED);
        job.setCurrentStep("Annullato dall'utente");
        contractAnalysisRepository.save(job);
        log.info("Job annullato | jobId={}", jobId);
    }

    public byte[] getResult(UUID jobId) {
        ContractAnalysis job = getJob(jobId);

        if (job.getStatus() != ContractAnalysis.JobStatus.COMPLETED) {
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
