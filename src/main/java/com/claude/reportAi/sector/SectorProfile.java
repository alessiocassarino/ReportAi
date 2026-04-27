package com.claude.reportAi.sector;

import java.util.List;

/**
 * Immutable descriptor of a business sector. Defines which workflows are available
 * and any sector-specific metadata used in prompt resolution.
 */
public record SectorProfile(
        String id,
        String displayName,
        String description,
        List<String> enabledWorkflows
) {
    public boolean hasWorkflow(String workflowId) {
        return enabledWorkflows.contains(workflowId);
    }
}
