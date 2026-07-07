package io.agentctl.api.workflow;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface RunWorkflow {
    String TASK_QUEUE = "agentctl-runs";

    @WorkflowMethod
    RunWorkflowResult run(RunWorkflowInput input);

    @SignalMethod
    void approve(ApprovalSignal signal);

    @QueryMethod
    RunWorkflowState state();
}
