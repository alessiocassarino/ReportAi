package com.claude.reportAi.service.pricecomparison;

import com.claude.reportAi.entities.PriceComparison;
import com.claude.reportAi.repository.PriceComparisonRepository;
import com.claude.reportAi.service.ModelChatClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PriceComparisonService {

    private final PriceComparisonRepository repository;
    private final PriceComparisonProcessor processor;

    public UUID startComparison(List<byte[]> fileContents, List<String> filenames, String model) {
        if (fileContents == null || fileContents.isEmpty()) {
            throw new IllegalArgumentException("Fornire almeno un file PDF.");
        }
        if (fileContents.size() < 2) {
            throw new IllegalArgumentException("Fornire almeno 2 offerte per effettuare il confronto.");
        }

        ModelChatClientFactory.findModel(model);

        PriceComparison job = new PriceComparison();
        job.setStatus(PriceComparison.JobStatus.PENDING);
        job.setModel(model);
        job.setOriginalFilenames(String.join(", ", filenames));
        job.setNumFiles(fileContents.size());
        job.setProgress(0);
        job.setCurrentStep("In attesa di elaborazione");
        repository.save(job);

        UUID jobId = job.getId();
        log.info("PriceComparisonJob creato: {} | model={} | numFiles={}", jobId, model, fileContents.size());

        processor.processAsync(jobId, fileContents, filenames, model);

        return jobId;
    }

    public PriceComparison getJob(UUID jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("Job non trovato: " + jobId));
    }

    public byte[] getResult(UUID jobId) {
        PriceComparison job = getJob(jobId);
        if (job.getStatus() != PriceComparison.JobStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Il job " + jobId + " non è ancora completato. Stato: " + job.getStatus());
        }
        byte[] content = job.getResultFileContent();
        if (content == null || content.length == 0) {
            throw new IllegalStateException("Contenuto del report non disponibile per il job: " + jobId);
        }
        return content;
    }
}
