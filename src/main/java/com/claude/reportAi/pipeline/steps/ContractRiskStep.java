package com.claude.reportAi.pipeline.steps;

import com.claude.reportAi.pipeline.PipelineContext;
import com.claude.reportAi.pipeline.PipelineStep;
import com.claude.reportAi.service.ContractAnalysisService;
import com.claude.reportAi.util.ByteArrayMultipartFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Pipeline step for contract risk analysis.
 * Delegates to the existing ContractAnalysisService (v1 logic reused as-is).
 * Stores the legacyJobId in the context for the PipelineEngine to persist.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContractRiskStep implements PipelineStep {

    private final ContractAnalysisService contractAnalysisService;

    @Override
    public String stepId() {
        return "contract-risk-step";
    }

    @Override
    public String execute(PipelineContext ctx) throws Exception {
        if (ctx.getFileContents().isEmpty()) {
            throw new IllegalArgumentException("Nessun file fornito per l'analisi del contratto.");
        }

        byte[] pdfBytes = ctx.getFileContents().getFirst();
        String filename = ctx.getFilenames().getFirst();

        ByteArrayMultipartFile file = new ByteArrayMultipartFile(pdfBytes, filename, "application/pdf");

        UUID legacyJobId = contractAnalysisService.startAnalysis(
                file, ctx.getModel(), null, ctx.getSector());

        log.info("ContractRiskStep delegato a legacy service | legacyJobId={} | sector={}",
                legacyJobId, ctx.getSector());

        return legacyJobId.toString();
    }
}
