package io.agentctl.api.workflow;

public record RunWorkflowInput(
        String tenantId,
        String runId,
        String agentId,
        String input) {
}
