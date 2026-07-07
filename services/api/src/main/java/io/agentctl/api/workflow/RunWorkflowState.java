package io.agentctl.api.workflow;

public record RunWorkflowState(
        String status,
        String approvalId) {
}
