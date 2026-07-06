package io.agentctl.api.controlplane;

import java.time.Instant;

public record RunResponse(
        String id,
        String agentId,
        String status,
        String input,
        Instant createdAt,
        Instant updatedAt) {
}
