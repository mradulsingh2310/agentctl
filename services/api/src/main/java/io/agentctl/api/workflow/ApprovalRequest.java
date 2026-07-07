package io.agentctl.api.workflow;

public record ApprovalRequest(
        String tenantId,
        String runId,
        String toolName,
        String question) {
}
