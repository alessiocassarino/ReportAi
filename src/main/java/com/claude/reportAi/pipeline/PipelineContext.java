package com.claude.reportAi.pipeline;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable context object passed through all steps of a pipeline execution.
 * Steps can read inputs and write intermediate results via the attributes map.
 */
@Getter
@Setter
public class PipelineContext {

    private UUID jobId;
    private String workflowId;
    private String sector;
    private String model;
    private List<byte[]> fileContents = new ArrayList<>();
    private List<String> filenames = new ArrayList<>();
    private UUID userId;

    private final Map<String, Object> attributes = new HashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (value == null) return null;
        return type.cast(value);
    }

    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    public static PipelineContext of(UUID jobId, String workflowId, String sector, String model,
                                     List<byte[]> fileContents, List<String> filenames, UUID userId) {
        PipelineContext ctx = new PipelineContext();
        ctx.setJobId(jobId);
        ctx.setWorkflowId(workflowId);
        ctx.setSector(sector);
        ctx.setModel(model);
        ctx.setFileContents(fileContents);
        ctx.setFilenames(filenames);
        ctx.setUserId(userId);
        return ctx;
    }
}
