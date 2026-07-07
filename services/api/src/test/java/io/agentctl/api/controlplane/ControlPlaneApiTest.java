package io.agentctl.api.controlplane;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentctl.api.workflow.ApprovalSignal;
import io.agentctl.api.workflow.RunWorkflowGateway;
import io.agentctl.api.workflow.RunWorkflowInput;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agentctl-control-plane;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
class ControlPlaneApiTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RecordingRunWorkflowGateway runWorkflowGateway;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("delete from audit_events").update();
        jdbc.sql("delete from approvals").update();
        jdbc.sql("delete from runs").update();
        jdbc.sql("delete from users").update();
        jdbc.sql("delete from tenants").update();
        runWorkflowGateway.reset();
    }

    @Test
    void createsListsAndReadsRunsWithinTenant() throws Exception {
        MvcResult created = mvc.perform(post("/api/runs")
                        .header("X-Agentctl-Tenant", "tenant_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentId": "support-ticket",
                                  "input": "Create a support ticket for login failure"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", startsWith("run_")))
                .andExpect(jsonPath("$.agentId").value("support-ticket"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn();

        String runId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        assertThat(runWorkflowGateway.startedRuns())
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.tenantId()).isEqualTo("tenant_a");
                    assertThat(run.runId()).isEqualTo(runId);
                    assertThat(run.agentId()).isEqualTo("support-ticket");
                    assertThat(run.input()).isEqualTo("Create a support ticket for login failure");
                });

        mvc.perform(get("/api/runs").header("X-Agentctl-Tenant", "tenant_a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(runId));

        mvc.perform(get("/api/runs/{runId}", runId).header("X-Agentctl-Tenant", "tenant_a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId));

        mvc.perform(get("/api/runs").header("X-Agentctl-Tenant", "tenant_b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        mvc.perform(get("/api/runs/{runId}", runId).header("X-Agentctl-Tenant", "tenant_b"))
                .andExpect(status().isNotFound());
    }

    @Test
    void recordsAuditTimelineWhenRunIsCreated() throws Exception {
        MvcResult created = mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentId": "support-ticket",
                                  "input": "Open a ticket"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String runId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(get("/api/runs/{runId}/audit", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].eventType").value("RUN_CREATED"))
                .andExpect(jsonPath("$.items[0].runId").value(runId));
    }

    @Test
    void approvesPendingApprovalsOnceAndWritesAudit() throws Exception {
        String runId = "run_pending_approval";
        String approvalId = "approval_pending";
        insertRun("tenant_a", runId);
        insertApproval("tenant_a", approvalId, runId, "github_issue.create");

        mvc.perform(get("/api/approvals/pending").header("X-Agentctl-Tenant", "tenant_a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(approvalId));

        mvc.perform(post("/api/approvals/{approvalId}/approve", approvalId)
                        .header("X-Agentctl-Tenant", "tenant_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actorId": "user_local",
                                  "reason": "Looks safe"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("user_local"));

        assertThat(runWorkflowGateway.approvalSignals())
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.runId()).isEqualTo(runId);
                    assertThat(signal.approvalId()).isEqualTo(approvalId);
                    assertThat(signal.decision()).isEqualTo("APPROVED");
                    assertThat(signal.actorId()).isEqualTo("user_local");
                    assertThat(signal.reason()).isEqualTo("Looks safe");
                });

        mvc.perform(post("/api/approvals/{approvalId}/approve", approvalId)
                        .header("X-Agentctl-Tenant", "tenant_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actorId": "user_local",
                                  "reason": "Again"
                                }
                                """))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/runs/{runId}/audit", runId).header("X-Agentctl-Tenant", "tenant_a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventType").value("APPROVAL_APPROVED"));
    }

    @Test
    void rejectsPendingApprovalsWithinTenant() throws Exception {
        String runId = "run_rejected_approval";
        String approvalId = "approval_rejected";
        insertRun("tenant_a", runId);
        insertApproval("tenant_a", approvalId, runId, "github_issue.create");

        mvc.perform(post("/api/approvals/{approvalId}/reject", approvalId)
                        .header("X-Agentctl-Tenant", "tenant_a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actorId": "user_local",
                                  "reason": "Needs more context"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionReason").value("Needs more context"));

        assertThat(runWorkflowGateway.approvalSignals())
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.runId()).isEqualTo(runId);
                    assertThat(signal.approvalId()).isEqualTo(approvalId);
                    assertThat(signal.decision()).isEqualTo("REJECTED");
                    assertThat(signal.actorId()).isEqualTo("user_local");
                    assertThat(signal.reason()).isEqualTo("Needs more context");
                });

        mvc.perform(get("/api/approvals/pending").header("X-Agentctl-Tenant", "tenant_a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        mvc.perform(post("/api/approvals/{approvalId}/reject", approvalId)
                        .header("X-Agentctl-Tenant", "tenant_b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actorId": "user_local",
                                  "reason": "Wrong tenant"
                                }
                                """))
                .andExpect(status().isNotFound());
    }

    private void insertRun(String tenantId, String runId) {
        Instant now = Instant.now();
        jdbc.sql("""
                        insert into tenants (id, display_name, created_at)
                        values (:tenant_id, :display_name, :created_at)
                        """)
                .param("tenant_id", tenantId)
                .param("display_name", tenantId)
                .param("created_at", Timestamp.from(now))
                .update();
        jdbc.sql("""
                        insert into runs (tenant_id, id, agent_id, status, input, created_at, updated_at)
                        values (:tenant_id, :id, :agent_id, :status, :input, :created_at, :updated_at)
                        """)
                .param("tenant_id", tenantId)
                .param("id", runId)
                .param("agent_id", "support-ticket")
                .param("status", "WAITING_FOR_APPROVAL")
                .param("input", "Needs approval")
                .param("created_at", Timestamp.from(now))
                .param("updated_at", Timestamp.from(now))
                .update();
    }

    private void insertApproval(String tenantId, String approvalId, String runId, String toolName) {
        Instant now = Instant.now();
        jdbc.sql("""
                        insert into approvals
                        (tenant_id, id, run_id, status, tool_name, question, created_at)
                        values (:tenant_id, :id, :run_id, :status, :tool_name, :question, :created_at)
                        """)
                .param("tenant_id", tenantId)
                .param("id", approvalId)
                .param("run_id", runId)
                .param("status", "PENDING")
                .param("tool_name", toolName)
                .param("question", "Approve tool call?")
                .param("created_at", Timestamp.from(now))
                .update();
    }

    @TestConfiguration
    static class WorkflowGatewayTestConfiguration {
        @Bean
        @Primary
        RecordingRunWorkflowGateway recordingRunWorkflowGateway() {
            return new RecordingRunWorkflowGateway();
        }
    }

    static class RecordingRunWorkflowGateway implements RunWorkflowGateway {
        private final List<RunWorkflowInput> startedRuns = new ArrayList<>();
        private final List<ApprovalSignal> approvalSignals = new ArrayList<>();

        @Override
        public void startRun(RunWorkflowInput input) {
            startedRuns.add(input);
        }

        @Override
        public void signalApproval(String runId, ApprovalSignal signal) {
            approvalSignals.add(signal);
        }

        List<RunWorkflowInput> startedRuns() {
            return startedRuns;
        }

        List<ApprovalSignal> approvalSignals() {
            return approvalSignals;
        }

        void reset() {
            startedRuns.clear();
            approvalSignals.clear();
        }
    }
}
