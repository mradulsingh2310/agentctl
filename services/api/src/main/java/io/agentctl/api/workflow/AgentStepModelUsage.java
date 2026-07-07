package io.agentctl.api.workflow;

public record AgentStepModelUsage(
        String provider,
        String model,
        int inputTokens,
        int outputTokens) {
}
