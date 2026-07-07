package io.agentctl.api.workflow;

import java.util.Map;

public record AgentStepRequest(
        String protocolVersion,
        String tenantId,
        String runId,
        String agentId,
        String stepId,
        String stepType,
        String input,
        Map<String, Object> modelProfile,
        Map<String, Object> toolContext,
        Map<String, Object> traceContext) {
}
