package io.agentctl.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

class RunWorkflowTest {
    private TestWorkflowEnvironment testWorkflowEnvironment;
    private WorkflowClient workflowClient;
    private RecordingRunProjectionActivities projectionActivities;
    private RecordingAgentStepActivities agentStepActivities;

    @BeforeEach
    void setUp() {
        testWorkflowEnvironment = TestWorkflowEnvironment.newInstance();
        Worker worker = testWorkflowEnvironment.newWorker(RunWorkflow.TASK_QUEUE);
        projectionActivities = new RecordingRunProjectionActivities();
        agentStepActivities = new RecordingAgentStepActivities();
        worker.registerWorkflowImplementationTypes(RunWorkflowImpl.class);
        worker.registerActivitiesImplementations(projectionActivities, agentStepActivities);
        workflowClient = testWorkflowEnvironment.getWorkflowClient();
        testWorkflowEnvironment.start();
    }

    @AfterEach
    void tearDown() {
        testWorkflowEnvironment.close();
    }

    @Test
    void invokesAgentStepThenWaitsForApprovalSignalThenCompletesRun() {
        agentStepActivities.enqueue(
                waitingForApproval("step_run_approved"),
                executeCompleted("step_execute_run_approved", "run_approved"));
        RunWorkflow workflow = newWorkflow("test-run-approved");

        WorkflowClient.start(workflow::run, input("run_approved"));

        RunWorkflowState waiting = waitForState(workflow, "WAITING_FOR_APPROVAL");
        assertThat(waiting.approvalId()).isEqualTo("approval_run_approved");

        workflow.approve(new ApprovalSignal(
                "run_approved",
                "approval_run_approved",
                "APPROVED",
                "user_local",
                "Looks safe"));

        RunWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(RunWorkflowResult.class);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(agentStepActivities.requests()).hasSize(2);
        AgentStepRequest draftRequest = agentStepActivities.requests().get(0);
        assertThat(draftRequest.tenantId()).isEqualTo("tenant_a");
        assertThat(draftRequest.runId()).isEqualTo("run_approved");
        assertThat(draftRequest.agentId()).isEqualTo("support-ticket");
        assertThat(draftRequest.stepId()).isEqualTo("step_run_approved");
        assertThat(draftRequest.stepType()).isEqualTo("draft_ticket");
        assertThat(draftRequest.input()).isEqualTo("Create a support ticket for login failure");

        AgentStepRequest executeRequest = agentStepActivities.requests().get(1);
        assertThat(executeRequest.stepId()).isEqualTo("step_execute_run_approved");
        assertThat(executeRequest.stepType()).isEqualTo("execute_ticket_workflow");
        assertThat(executeRequest.toolContext()).containsEntry("backend", "fake");
        assertThat(executeRequest.toolContext()).containsEntry("operationBaseId", "run_approved:approval_run_approved");
        assertThat(executeRequest.toolContext()).containsKeys("approval", "draft");
        assertThat(projectionActivities.calls()).containsExactly(
                "RUNNING:run_approved",
                "STEP:run_approved:WAITING_FOR_APPROVAL",
                "APPROVAL_REQUESTED:run_approved:support_ticket.approve_draft",
                "STEP:run_approved:COMPLETED",
                "COMPLETED:run_approved:user_local");
    }

    @Test
    void invokesAgentStepThenWaitsForApprovalSignalThenRejectsRun() {
        agentStepActivities.enqueue(waitingForApproval("step_run_rejected"));
        RunWorkflow workflow = newWorkflow("test-run-rejected");

        WorkflowClient.start(workflow::run, input("run_rejected"));

        RunWorkflowState waiting = waitForState(workflow, "WAITING_FOR_APPROVAL");
        assertThat(waiting.approvalId()).isEqualTo("approval_run_rejected");

        workflow.approve(new ApprovalSignal(
                "run_rejected",
                "approval_run_rejected",
                "REJECTED",
                "user_local",
                "Needs more context"));

        RunWorkflowResult result = WorkflowStub.fromTyped(workflow).getResult(RunWorkflowResult.class);

        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(agentStepActivities.requests())
                .singleElement()
                .satisfies(request -> assertThat(request.stepType()).isEqualTo("draft_ticket"));
        assertThat(projectionActivities.calls()).containsExactly(
                "RUNNING:run_rejected",
                "STEP:run_rejected:WAITING_FOR_APPROVAL",
                "APPROVAL_REQUESTED:run_rejected:support_ticket.approve_draft",
                "REJECTED:run_rejected:user_local");
    }

    @Test
    void completesRunWhenAgentStepCompletesWithoutApproval() {
        agentStepActivities.enqueue(completed("step_run_completed"));
        RunWorkflow workflow = newWorkflow("test-run-completed");

        RunWorkflowResult result = workflow.run(input("run_completed"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(projectionActivities.calls()).containsExactly(
                "RUNNING:run_completed",
                "STEP:run_completed:COMPLETED",
                "COMPLETED:run_completed:agentctl-worker");
    }

    @Test
    void failsRunWhenAgentStepFails() {
        agentStepActivities.enqueue(failed("step_run_failed"));
        RunWorkflow workflow = newWorkflow("test-run-failed");

        RunWorkflowResult result = workflow.run(input("run_failed"));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(projectionActivities.calls()).containsExactly(
                "RUNNING:run_failed",
                "STEP:run_failed:FAILED",
                "FAILED:run_failed:UNSUPPORTED_AGENT");
    }

    @Test
    void failsRunWhenAgentStepActivityFailsAfterRetries() {
        agentStepActivities.failure = new IllegalStateException("agent worker unavailable");
        RunWorkflow workflow = newWorkflow("test-run-activity-failed");

        RunWorkflowResult result = workflow.run(input("run_activity_failed"));

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(agentStepActivities.requests()).hasSize(3);
        assertThat(projectionActivities.calls()).containsExactly(
                "RUNNING:run_activity_failed",
                "FAILED:run_activity_failed:AGENT_STEP_ACTIVITY_FAILED");
    }

    private RunWorkflow newWorkflow(String workflowId) {
        return workflowClient.newWorkflowStub(
                RunWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(RunWorkflow.TASK_QUEUE)
                        .build());
    }

    private static RunWorkflowInput input(String runId) {
        return new RunWorkflowInput(
                "tenant_a",
                runId,
                "support-ticket",
                "Create a support ticket for login failure");
    }

    private static AgentStepResponse waitingForApproval(String stepId) {
        return new AgentStepResponse(
                "2026-07-07",
                stepId,
                "WAITING_FOR_APPROVAL",
                "Drafted a support ticket.",
                Map.of("ticket", draftTicket()),
                new AgentStepApprovalRequest(
                        "support_ticket.approve_draft",
                        "Approve this ticket draft before any ticket backend mutation?"),
                List.of(),
                new AgentStepModelUsage("stub", "stub", 0, 0),
                null);
    }

    private static AgentStepResponse executeCompleted(String stepId, String runId) {
        return new AgentStepResponse(
                "2026-07-07",
                stepId,
                "COMPLETED",
                "Created fake ticket fake_" + runId + ".",
                Map.of("ticket", executedTicket(runId)),
                null,
                List.of(new AgentStepToolCall(
                        "fake_ticket.create",
                        runId + ":approval_" + runId + ":fake_ticket.create",
                        "COMPLETED",
                        "fake",
                        null,
                        "local-dev",
                        Map.of("externalTicketId", "fake_" + runId))),
                new AgentStepModelUsage("stub", "stub", 0, 0),
                null);
    }

    private static Map<String, Object> draftTicket() {
        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("title", "Login fails");
        ticket.put("body", "User reported login failure.");
        ticket.put("severity", "medium");
        ticket.put("labels", List.of("bug", "login"));
        ticket.put("assignee", null);
        return ticket;
    }

    private static Map<String, Object> executedTicket(String runId) {
        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("backend", "fake");
        ticket.put("externalTicketId", "fake_" + runId);
        ticket.put("externalUrl", null);
        ticket.put("title", "Login fails");
        ticket.put("body", "User reported login failure.");
        ticket.put("status", "OPEN");
        ticket.put("severity", "medium");
        ticket.put("labels", List.of("bug", "login"));
        ticket.put("assignee", null);
        ticket.put("idempotencyMarker", "agentctl:" + runId + ":approval_" + runId + ":fake_ticket.create");
        return ticket;
    }

    private static AgentStepResponse completed(String stepId) {
        return new AgentStepResponse(
                "2026-07-07",
                stepId,
                "COMPLETED",
                "No external action required.",
                Map.of(),
                null,
                List.of(),
                new AgentStepModelUsage("stub", "stub", 0, 0),
                null);
    }

    private static AgentStepResponse failed(String stepId) {
        return new AgentStepResponse(
                "2026-07-07",
                stepId,
                "FAILED",
                "Unsupported agent.",
                Map.of(),
                null,
                List.of(),
                null,
                new AgentStepError("UNSUPPORTED_AGENT", "No agent is registered", false));
    }

    private static RunWorkflowState waitForState(RunWorkflow workflow, String expectedStatus) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        RunWorkflowState latest = null;
        while (System.nanoTime() < deadline) {
            latest = workflow.state();
            if (expectedStatus.equals(latest.status())) {
                return latest;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for workflow state", e);
            }
        }
        fail("Expected workflow state <%s> but last state was <%s>", expectedStatus, latest);
        throw new IllegalStateException("unreachable");
    }

    static class RecordingRunProjectionActivities implements RunProjectionActivities {
        private final List<String> calls = new ArrayList<>();

        @Override
        public void markRunRunning(RunWorkflowInput input) {
            calls.add("RUNNING:" + input.runId());
        }

        @Override
        public void recordAgentStep(AgentStepRequest request, AgentStepResponse response) {
            calls.add("STEP:" + request.runId() + ":" + response.status());
        }

        @Override
        public ApprovalRequestResult requestApproval(ApprovalRequest request) {
            calls.add("APPROVAL_REQUESTED:" + request.runId() + ":" + request.toolName());
            return new ApprovalRequestResult("approval_" + request.runId());
        }

        @Override
        public void completeRun(RunCompletion completion) {
            calls.add("COMPLETED:" + completion.runId() + ":" + completion.actorId());
        }

        @Override
        public void rejectRun(RunRejection rejection) {
            calls.add("REJECTED:" + rejection.runId() + ":" + rejection.actorId());
        }

        @Override
        public void failRun(RunFailure failure) {
            calls.add("FAILED:" + failure.runId() + ":" + failure.errorCode());
        }

        List<String> calls() {
            return calls;
        }
    }

    static class RecordingAgentStepActivities implements AgentStepActivities {
        private final List<AgentStepRequest> requests = new ArrayList<>();
        private final List<AgentStepResponse> responses = new ArrayList<>();
        private RuntimeException failure;

        @Override
        public AgentStepResponse callAgentStep(AgentStepRequest request) {
            requests.add(request);
            if (failure != null) {
                throw failure;
            }
            if (responses.isEmpty()) {
                return waitingForApproval("step_default");
            }
            return responses.remove(0);
        }

        List<AgentStepRequest> requests() {
            return requests;
        }

        void enqueue(AgentStepResponse... responses) {
            this.responses.addAll(List.of(responses));
        }
    }
}
