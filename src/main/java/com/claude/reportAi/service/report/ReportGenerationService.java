package com.claude.reportAi.service.report;

import com.claude.reportAi.entities.ReportJob;
import com.claude.reportAi.repository.ReportJobRepository;
import com.claude.reportAi.service.ModelChatClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportGenerationService {

    private final ReportJobRepository reportJobRepository;
    private final ReportGenerationProcessor processor;

    public UUID startGeneration(byte[] pdfBytes, String originalFilename, String model) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("Il file PDF non può essere vuoto.");
        }

        // Valida modello (lancia IllegalArgumentException se non supportato)
        ModelChatClientFactory.findModel(model);

        ReportJob job = new ReportJob();
        job.setStatus(ReportJob.JobStatus.PENDING);
        job.setModel(model);
        job.setOriginalFilename(originalFilename);
        job.setProgress(0);
        job.setCurrentStep("In attesa di elaborazione");
        reportJobRepository.save(job);

        UUID jobId = job.getId();
        log.info("ReportJob creato: {} | model={} | file={}", jobId, model, originalFilename);

        processor.processAsync(jobId, pdfBytes, originalFilename, model);

        return jobId;
    }

    public ReportJob getJob(UUID jobId) {
        return reportJobRepository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("Job non trovato: " + jobId));
    }

    public byte[] getResult(UUID jobId) {
        ReportJob job = getJob(jobId);
        if (job.getStatus() != ReportJob.JobStatus.COMPLETED) {
            throw new IllegalStateException("Il job " + jobId + " non è ancora completato. Stato: " + job.getStatus());
        }
        byte[] content = job.getResultFileContent();
        if (content == null || content.length == 0) {
            throw new IllegalStateException("Contenuto del report non disponibile per il job: " + jobId);
        }
        return content;
    }
}
