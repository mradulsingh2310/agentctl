package io.agentctl.api.workflow;

import java.util.List;
import java.util.Map;

public record AgentStepResponse(
        String protocolVersion,
        String stepId,
        String status,
        String summary,
        Map<String, Object> output,
        AgentStepApprovalRequest approvalRequest,
        List<AgentStepToolCall> toolCalls,
        AgentStepModelUsage modelUsage,
        AgentStepError error) {
}
