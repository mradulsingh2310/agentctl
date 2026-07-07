package io.agentctl.api.workflow;

public record AgentStepError(
        String code,
        String message,
        boolean retryable) {
}
