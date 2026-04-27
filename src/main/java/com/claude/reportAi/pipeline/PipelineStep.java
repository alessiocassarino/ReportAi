package com.claude.reportAi.pipeline;

/**
 * Represents a single executable unit in a pipeline workflow.
 * Steps are stateless and receive all needed data via PipelineContext.
 */
public interface PipelineStep {

    /**
     * Unique identifier for this step, used in WorkflowDefinition.steps.
     */
    String stepId();

    /**
     * Execute the step logic. Reads inputs from ctx and writes results back.
     * May throw any exception — the PipelineEngine handles failure propagation.
     */
    String execute(PipelineContext ctx) throws Exception;
}
