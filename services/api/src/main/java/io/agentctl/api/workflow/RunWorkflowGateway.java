package io.agentctl.api.workflow;

public interface RunWorkflowGateway {
    void startRun(RunWorkflowInput input);

    void signalApproval(String runId, ApprovalSignal signal);
}
