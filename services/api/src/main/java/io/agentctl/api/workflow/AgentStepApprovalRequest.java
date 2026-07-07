package io.agentctl.api.workflow;

public record AgentStepApprovalRequest(
        String toolName,
        String question) {
}
