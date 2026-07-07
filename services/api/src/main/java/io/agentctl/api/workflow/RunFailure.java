package io.agentctl.api.workflow;

public record RunFailure(
        String tenantId,
        String runId,
        String errorCode,
        String errorMessage) {
}
