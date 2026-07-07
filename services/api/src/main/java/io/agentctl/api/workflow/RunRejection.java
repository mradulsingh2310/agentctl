package io.agentctl.api.workflow;

public record RunRejection(
        String tenantId,
        String runId,
        String approvalId,
        String actorId,
        String reason) {
}
