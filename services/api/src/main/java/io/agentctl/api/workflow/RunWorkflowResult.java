package io.agentctl.api.workflow;

public record RunWorkflowResult(
        String runId,
        String status) {
}
