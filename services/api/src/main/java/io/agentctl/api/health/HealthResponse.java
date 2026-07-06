package io.agentctl.api.health;

public record HealthResponse(
        String service,
        String status,
        boolean temporalExecutionTruth,
        boolean authoritativeWal) {
}
