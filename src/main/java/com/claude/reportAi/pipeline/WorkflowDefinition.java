package com.claude.reportAi.pipeline;

import java.util.List;

/**
 * Immutable descriptor of a workflow: which steps to run, which sectors support it,
 * and file constraints.
 */
public record WorkflowDefinition(
        String id,
        String displayName,
        String description,
        List<String> supportedSectors,
        List<String> stepIds,
        int minFiles,
        int maxFiles,
        String acceptedMimeType
) {
    public boolean supportsSector(String sector) {
        return supportedSectors.contains(sector);
    }

    public boolean acceptsFileCount(int count) {
        return count >= minFiles && count <= maxFiles;
    }
}
