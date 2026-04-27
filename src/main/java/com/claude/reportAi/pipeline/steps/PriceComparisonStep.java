package com.claude.reportAi.pipeline.steps;

import com.claude.reportAi.pipeline.PipelineContext;
import com.claude.reportAi.pipeline.PipelineStep;
import com.claude.reportAi.service.pricecomparison.PriceComparisonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Pipeline step for supplier offer comparison.
 * Delegates to the existing PriceComparisonService (v1 logic reused as-is).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PriceComparisonStep implements PipelineStep {

    private final PriceComparisonService priceComparisonService;

    @Override
    public String stepId() {
        return "price-comparison-step";
    }

    @Override
    public String execute(PipelineContext ctx) throws Exception {
        if (ctx.getFileContents().size() < 2) {
            throw new IllegalArgumentException("Sono necessari almeno 2 file per il confronto offerte.");
        }

        UUID legacyJobId = priceComparisonService.startComparison(
                ctx.getFileContents(), ctx.getFilenames(), ctx.getModel(), null, ctx.getSector());

        log.info("PriceComparisonStep delegato a legacy service | legacyJobId={} | sector={}",
                legacyJobId, ctx.getSector());

        return legacyJobId.toString();
    }
}
