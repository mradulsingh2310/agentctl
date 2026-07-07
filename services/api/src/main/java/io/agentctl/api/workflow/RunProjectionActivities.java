package io.agentctl.api.workflow;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface RunProjectionActivities {
    void markRunRunning(RunWorkflowInput input);

    void recordAgentStep(AgentStepRequest request, AgentStepResponse response);

    ApprovalRequestResult requestApproval(ApprovalRequest request);

    void completeRun(RunCompletion completion);

    void rejectRun(RunRejection rejection);

    void failRun(RunFailure failure);
}
