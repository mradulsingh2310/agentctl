package io.agentctl.api.workflow;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface RunProjectionActivities {
    void markRunRunning(RunWorkflowInput input);

    ApprovalRequestResult requestApproval(ApprovalRequest request);

    void completeRun(RunCompletion completion);

    void rejectRun(RunRejection rejection);
}
