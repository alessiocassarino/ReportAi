package com.claude.reportAi.pipeline.steps;

import com.claude.reportAi.pipeline.PipelineContext;
import com.claude.reportAi.pipeline.PipelineStep;
import com.claude.reportAi.service.estimate.EstimateGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Pipeline step for EPC estimate generation.
 * Delegates to the existing EstimateGenerationService (v1 logic reused as-is).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EstimateStep implements PipelineStep {

    private final EstimateGenerationService estimateGenerationService;

    @Override
    public String stepId() {
        return "estimate-step";
    }

    @Override
    public String execute(PipelineContext ctx) throws Exception {
        if (ctx.getFileContents().isEmpty()) {
            throw new IllegalArgumentException("Nessun file fornito per la generazione del preventivo.");
        }

        byte[] pdfBytes = ctx.getFileContents().getFirst();
        String filename = ctx.getFilenames().getFirst();

        UUID legacyJobId = estimateGenerationService.startGeneration(
                pdfBytes, filename, ctx.getModel(), null, ctx.getSector());

        log.info("EstimateStep delegato a legacy service | legacyJobId={} | sector={}",
                legacyJobId, ctx.getSector());

        return legacyJobId.toString();
    }
}
