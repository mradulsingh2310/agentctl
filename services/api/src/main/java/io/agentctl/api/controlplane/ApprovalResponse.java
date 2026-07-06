package io.agentctl.api.controlplane;

import java.time.Instant;

public record ApprovalResponse(
        String id,
        String runId,
        String status,
        String toolName,
        String question,
        Instant createdAt,
        Instant decidedAt,
        String decidedBy,
        String decisionReason) {
}
