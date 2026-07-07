package io.agentctl.api.workflow;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;

public class RunWorkflowImpl implements RunWorkflow {
    private static final ActivityOptions PROJECTION_ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .build();
    private static final ActivityOptions AGENT_STEP_ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofMillis(100))
                    .setMaximumInterval(Duration.ofSeconds(1))
                    .setMaximumAttempts(3)
                    .build())
            .build();

    private final RunProjectionActivities activities = Workflow.newActivityStub(
            RunProjectionActivities.class,
            PROJECTION_ACTIVITY_OPTIONS);
    private final AgentStepActivities agentSteps = Workflow.newActivityStub(
            AgentStepActivities.class,
            AGENT_STEP_ACTIVITY_OPTIONS);

    private String status = "CREATED";
    private String runId;
    private String approvalId;
    private ApprovalSignal approvalSignal;

    @Override
    public RunWorkflowResult run(RunWorkflowInput input) {
        runId = input.runId();
        status = "RUNNING";
        activities.markRunRunning(input);

        AgentStepRequest stepRequest = new AgentStepRequest(
                "2026-07-07",
                input.tenantId(),
                input.runId(),
                input.agentId(),
                "step_" + input.runId(),
                "draft_ticket",
                input.input(),
                Map.of("provider", "stub", "model", "stub"),
                Map.of("backend", "none"),
                Map.of());
        AgentStepResponse stepResponse;
        try {
            stepResponse = agentSteps.callAgentStep(stepRequest);
        } catch (ActivityFailure failure) {
            activities.failRun(new RunFailure(
                    input.tenantId(),
                    input.runId(),
                    "AGENT_STEP_ACTIVITY_FAILED",
                    failure.getMessage()));
            status = "FAILED";
            return new RunWorkflowResult(input.runId(), status);
        }
        activities.recordAgentStep(stepRequest, stepResponse);

        if ("FAILED".equals(stepResponse.status())) {
            AgentStepError error = stepResponse.error();
            activities.failRun(new RunFailure(
                    input.tenantId(),
                    input.runId(),
                    error == null ? "AGENT_STEP_FAILED" : error.code(),
                    error == null ? stepResponse.summary() : error.message()));
            status = "FAILED";
            return new RunWorkflowResult(input.runId(), status);
        }

        if ("COMPLETED".equals(stepResponse.status())) {
            activities.completeRun(new RunCompletion(
                    input.tenantId(),
                    input.runId(),
                    null,
                    "agentctl-worker",
                    stepResponse.summary()));
            status = "COMPLETED";
            return new RunWorkflowResult(input.runId(), status);
        }

        AgentStepApprovalRequest approvalRequest = stepResponse.approvalRequest();
        ApprovalRequestResult approval = activities.requestApproval(new ApprovalRequest(
                input.tenantId(),
                input.runId(),
                approvalRequest.toolName(),
                approvalRequest.question()));
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
