package io.agentctl.api.workflow;

final class NoopRunWorkflowGateway implements RunWorkflowGateway {
    @Override
    public void startRun(RunWorkflowInput input) {
    }

    @Override
    public void signalApproval(String runId, ApprovalSignal signal) {
    }
}
