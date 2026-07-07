package io.agentctl.api.workflow;

public record RunCompletion(
        String tenantId,
        String runId,
        String approvalId,
        String actorId,
        String reason) {
}
