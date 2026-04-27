package com.claude.reportAi.service.estimate;

import com.claude.reportAi.entities.Estimate;
import com.claude.reportAi.repository.EstimateRepository;
import com.claude.reportAi.service.ModelChatClientFactory;
import com.claude.reportAi.util.FileNameSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EstimateGenerationService {

    private final EstimateRepository estimateRepository;
    private final EstimateGenerationProcessor processor;

    public UUID startGeneration(byte[] pdfBytes, String originalFilename, String model, String customOutputFileName) {
        return startGeneration(pdfBytes, originalFilename, model, customOutputFileName, "OIL_GAS");
    }

    public UUID startGeneration(byte[] pdfBytes, String originalFilename, String model, String customOutputFileName, String sector) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("Il file PDF non può essere vuoto.");
        }

        // Valida modello (lancia IllegalArgumentException se non supportato)
        ModelChatClientFactory.findModel(model);

        Estimate job = new Estimate();
        job.setStatus(Estimate.JobStatus.PENDING);
        job.setModel(model);
        job.setOriginalFilename(originalFilename);
        job.setProgress(0);
        job.setCurrentStep("In attesa di elaborazione");
        job.setSector(sector != null ? sector : "OIL_GAS");

        String sanitizedOutputFileName = FileNameSanitizer.sanitize(customOutputFileName);
        if (sanitizedOutputFileName != null) {
            job.setResultFileName(sanitizedOutputFileName);
        }

        estimateRepository.save(job);

        UUID jobId = job.getId();
        log.info("ReportJob creato: {} | model={} | file={}", jobId, model, originalFilename);

        processor.processAsync(jobId, pdfBytes, originalFilename, model);

        return jobId;
    }

    public Estimate getJob(UUID jobId) {
        return estimateRepository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("Job non trovato: " + jobId));
    }

    public void cancelJob(UUID jobId) {
        Estimate job = getJob(jobId);
        if (job.getStatus() == Estimate.JobStatus.COMPLETED
                || job.getStatus() == Estimate.JobStatus.FAILED
                || job.getStatus() == Estimate.JobStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Impossibile annullare un job già terminato. Stato: " + job.getStatus());
        }
        job.setStatus(Estimate.JobStatus.CANCELLED);
        job.setCurrentStep("Annullato dall'utente");
        estimateRepository.save(job);
        log.info("Job annullato | jobId={}", jobId);
    }

    public byte[] getResult(UUID jobId) {
        Estimate job = getJob(jobId);
        if (job.getStatus() != Estimate.JobStatus.COMPLETED) {
            throw new IllegalStateException("Il job " + jobId + " non è ancora completato. Stato: " + job.getStatus());
        }
        byte[] content = job.getResultFileContent();
        if (content == null || content.length == 0) {
            throw new IllegalStateException("Contenuto del report non disponibile per il job: " + jobId);
        }
        return content;
    }
}
