package io.agentctl.api.controlplane;

import java.time.Instant;

public record AuditEventResponse(
        String id,
        String runId,
        String eventType,
        String message,
        Instant createdAt) {
}
