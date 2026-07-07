package io.agentctl.api.workflow;

public record ApprovalSignal(
        String runId,
        String approvalId,
        String decision,
        String actorId,
        String reason) {
}
