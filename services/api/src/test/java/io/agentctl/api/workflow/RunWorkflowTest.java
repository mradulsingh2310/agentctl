package io.agentctl.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    private RecordingRunProjectionActivities activities;

    @BeforeEach
    void setUp() {
        testWorkflowEnvironment = TestWorkflowEnvironment.newInstance();
        Worker worker = testWorkflowEnvironment.newWorker(RunWorkflow.TASK_QUEUE);
        activities = new RecordingRunProjectionActivities();
        worker.registerWorkflowImplementationTypes(RunWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        workflowClient = testWorkflowEnvironment.getWorkflowClient();
        testWorkflowEnvironment.start();
    }

    @AfterEach
    void tearDown() {
        testWorkflowEnvironment.close();
    }

    @Test
    void waitsForApprovalSignalThenCompletesRun() {
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
        assertThat(activities.calls()).containsExactly(
                "RUNNING:run_approved",
                "APPROVAL_REQUESTED:run_approved",
                "COMPLETED:run_approved:user_local");
    }

    @Test
    void waitsForApprovalSignalThenRejectsRun() {
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
        assertThat(activities.calls()).containsExactly(
                "RUNNING:run_rejected",
                "APPROVAL_REQUESTED:run_rejected",
                "REJECTED:run_rejected:user_local");
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
        public ApprovalRequestResult requestApproval(ApprovalRequest request) {
            calls.add("APPROVAL_REQUESTED:" + request.runId());
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

        List<String> calls() {
            return calls;
        }
    }
}
