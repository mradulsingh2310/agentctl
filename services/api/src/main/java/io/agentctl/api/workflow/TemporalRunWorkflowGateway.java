package io.agentctl.api.workflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;

final class TemporalRunWorkflowGateway implements RunWorkflowGateway {
    private final WorkflowClient workflowClient;
    private final String taskQueue;

    TemporalRunWorkflowGateway(WorkflowClient workflowClient, String taskQueue) {
        this.workflowClient = workflowClient;
        this.taskQueue = taskQueue;
    }

    @Override
    public void startRun(RunWorkflowInput input) {
        RunWorkflow workflow = workflowClient.newWorkflowStub(
                RunWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId(input.runId()))
                        .setTaskQueue(taskQueue)
                        .build());
        WorkflowClient.start(workflow::run, input);
    }

    @Override
    public void signalApproval(String runId, ApprovalSignal signal) {
        RunWorkflow workflow = workflowClient.newWorkflowStub(RunWorkflow.class, workflowId(runId));
        workflow.approve(signal);
    }

    private static String workflowId(String runId) {
        return "agentctl-run-" + runId;
    }
}
