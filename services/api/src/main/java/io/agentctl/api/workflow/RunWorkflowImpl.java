package io.agentctl.api.workflow;

import java.time.Duration;
import java.util.Locale;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;

public class RunWorkflowImpl implements RunWorkflow {
    private final RunProjectionActivities activities = Workflow.newActivityStub(
            RunProjectionActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .build());

    private String status = "CREATED";
    private String runId;
    private String approvalId;
    private ApprovalSignal approvalSignal;

    @Override
    public RunWorkflowResult run(RunWorkflowInput input) {
        runId = input.runId();
        status = "RUNNING";
        activities.markRunRunning(input);

        ApprovalRequestResult approval = activities.requestApproval(new ApprovalRequest(
                input.tenantId(),
                input.runId(),
                "support_ticket.approve",
                "Approve support-ticket agent action?"));
        approvalId = approval.approvalId();
        status = "WAITING_FOR_APPROVAL";

        Workflow.await(() -> approvalSignal != null);

        if ("APPROVED".equals(normalizedDecision())) {
            activities.completeRun(new RunCompletion(
                    input.tenantId(),
                    input.runId(),
                    approvalId,
                    approvalSignal.actorId(),
                    approvalSignal.reason()));
            status = "COMPLETED";
            return new RunWorkflowResult(input.runId(), status);
        }

        activities.rejectRun(new RunRejection(
                input.tenantId(),
                input.runId(),
                approvalId,
                approvalSignal.actorId(),
                approvalSignal.reason()));
        status = "REJECTED";
        return new RunWorkflowResult(input.runId(), status);
    }

    @Override
    public void approve(ApprovalSignal signal) {
        if (approvalSignal != null || runId == null || approvalId == null) {
            return;
        }
        if (!runId.equals(signal.runId()) || !approvalId.equals(signal.approvalId())) {
            return;
        }
        approvalSignal = signal;
    }

    @Override
    public RunWorkflowState state() {
        return new RunWorkflowState(status, approvalId);
    }

    private String normalizedDecision() {
        return approvalSignal.decision().toUpperCase(Locale.ROOT);
    }
}
