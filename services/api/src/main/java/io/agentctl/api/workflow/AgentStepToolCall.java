package io.agentctl.api.workflow;

import java.util.Map;

public record AgentStepToolCall(
        String toolName,
        String operationId,
        String status,
        String backend,
        String externalUrl,
        String fgaDecisionId,
        Map<String, Object> metadata) {
}
