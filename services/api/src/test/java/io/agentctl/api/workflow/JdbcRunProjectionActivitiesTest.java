package io.agentctl.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agentctl-projections;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "agentctl.temporal.enabled=false"
})
class JdbcRunProjectionActivitiesTest {
    @Autowired
    private JdbcRunProjectionActivities activities;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("delete from tickets").update();
        jdbc.sql("delete from tool_calls").update();
        jdbc.sql("delete from model_calls").update();
        jdbc.sql("delete from agent_steps").update();
        jdbc.sql("delete from audit_events").update();
        jdbc.sql("delete from approvals").update();
        jdbc.sql("delete from runs").update();
        jdbc.sql("delete from users").update();
        jdbc.sql("delete from tenants").update();
        insertRun("tenant_a", "run_projection");
    }

    @Test
    void recordsAgentStepModelToolAndAuditProjection() {
        activities.recordAgentStep(stepRequest(), waitingForApprovalResponse());

        assertThat(count("agent_steps")).isEqualTo(1);
        assertThat(count("model_calls")).isEqualTo(1);
        assertThat(count("tool_calls")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
        assertThat(singleString("select status from agent_steps where id = 'step_run_projection'"))
                .isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(singleString("select event_type from audit_events where run_id = 'run_projection'"))
                .isEqualTo("AGENT_STEP_RECORDED");
    }

    @Test
    void recordingSameAgentStepTwiceIsIdempotentForTemporalActivityRetries() {
        activities.recordAgentStep(stepRequest(), waitingForApprovalResponse());
        activities.recordAgentStep(stepRequest(), waitingForApprovalResponse());

        assertThat(count("agent_steps")).isEqualTo(1);
        assertThat(count("model_calls")).isEqualTo(1);
        assertThat(count("tool_calls")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
    }

    @Test
    void recordsCompletedExecuteStepTicketToolAndAuditProjection() {
        activities.recordAgentStep(executeStepRequest(), executeCompletedResponse());

        assertThat(count("agent_steps")).isEqualTo(1);
        assertThat(count("tickets")).isEqualTo(1);
        assertThat(count("tool_calls")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
        assertThat(singleString("select backend from tickets where run_id = 'run_projection'"))
                .isEqualTo("fake");
        assertThat(singleString("select external_ticket_id from tickets where run_id = 'run_projection'"))
                .isEqualTo("fake_run_projection");
        assertThat(singleString("select operation_id from tool_calls where run_id = 'run_projection'"))
                .isEqualTo("run_projection:approval_run_projection:fake_ticket.create");
    }

    @Test
    void recordingSameExecuteStepTwiceDoesNotDuplicateTicketProjection() {
        activities.recordAgentStep(executeStepRequest(), executeCompletedResponse());
        activities.recordAgentStep(executeStepRequest(), executeCompletedResponse());

        assertThat(count("agent_steps")).isEqualTo(1);
        assertThat(count("tickets")).isEqualTo(1);
        assertThat(count("tool_calls")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
    }

    @Test
    void failRunUpdatesRunStatusAndAudit() {
        activities.failRun(new RunFailure("tenant_a", "run_projection", "UNSUPPORTED_AGENT", "No agent"));

        assertThat(singleString("select status from runs where id = 'run_projection'"))
                .isEqualTo("FAILED");
        assertThat(singleString("select event_type from audit_events where run_id = 'run_projection'"))
                .isEqualTo("RUN_FAILED");
    }

    @Test
    void completeRunWithoutApprovalUpdatesRunAndAuditOnly() {
        activities.completeRun(new RunCompletion(
                "tenant_a",
                "run_projection",
                null,
                "agentctl-worker",
                "No approval required"));

        assertThat(singleString("select status from runs where id = 'run_projection'"))
                .isEqualTo("COMPLETED");
        assertThat(count("approvals")).isEqualTo(0);
        assertThat(singleString("select event_type from audit_events where run_id = 'run_projection'"))
                .isEqualTo("RUN_COMPLETED");
    }

    private AgentStepRequest stepRequest() {
        return new AgentStepRequest(
                "2026-07-07",
                "tenant_a",
                "run_projection",
                "support-ticket",
                "step_run_projection",
                "draft_ticket",
                "Create a support ticket",
                Map.of("provider", "stub", "model", "stub"),
                Map.of("backend", "fake"),
                Map.of());
    }

    private AgentStepRequest executeStepRequest() {
        return new AgentStepRequest(
                "2026-07-07",
                "tenant_a",
                "run_projection",
                "support-ticket",
                "step_execute_run_projection",
                "execute_ticket_workflow",
                "Create a support ticket",
                Map.of("provider", "stub", "model", "stub"),
                Map.of("backend", "fake"),
                Map.of());
    }

    private AgentStepResponse waitingForApprovalResponse() {
        return new AgentStepResponse(
                "2026-07-07",
                "step_run_projection",
                "WAITING_FOR_APPROVAL",
                "Drafted a support ticket.",
                Map.of("ticket", Map.of("title", "Login failure")),
                new AgentStepApprovalRequest(
                        "support_ticket.approve_draft",
                        "Approve this ticket draft before any ticket backend mutation?"),
                List.of(new AgentStepToolCall(
                        "support_ticket.approve_draft",
                        "op_run_projection",
                        "WAITING_FOR_APPROVAL",
                        "fake",
                        null,
                        null,
                        Map.of("ticketTitle", "Login failure"))),
                new AgentStepModelUsage("stub", "stub", 0, 0),
                null);
    }

    private AgentStepResponse executeCompletedResponse() {
        return new AgentStepResponse(
                "2026-07-07",
                "step_execute_run_projection",
                "COMPLETED",
                "Created fake ticket fake_run_projection.",
                Map.of("ticket", ticketOutput()),
                null,
                List.of(new AgentStepToolCall(
                        "fake_ticket.create",
                        "run_projection:approval_run_projection:fake_ticket.create",
                        "COMPLETED",
                        "fake",
                        null,
                        "local-dev",
                        Map.of("externalTicketId", "fake_run_projection"))),
                new AgentStepModelUsage("stub", "stub", 0, 0),
                null);
    }

    private Map<String, Object> ticketOutput() {
        Map<String, Object> ticket = new LinkedHashMap<>();
        ticket.put("backend", "fake");
        ticket.put("externalTicketId", "fake_run_projection");
        ticket.put("externalUrl", null);
        ticket.put("title", "Checkout fails with HTTP 500");
        ticket.put("body", "User reported checkout failing with HTTP 500.");
        ticket.put("status", "OPEN");
        ticket.put("severity", "high");
        ticket.put("labels", List.of("bug", "checkout"));
        ticket.put("assignee", null);
        ticket.put("idempotencyMarker", "agentctl:run_projection:approval_run_projection:fake_ticket.create");
        return ticket;
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
                .param("status", "CREATED")
                .param("input", "Create a support ticket")
                .param("created_at", Timestamp.from(now))
                .param("updated_at", Timestamp.from(now))
                .update();
    }

    private Integer count(String table) {
        return jdbc.sql("select count(*) from " + table)
                .query(Integer.class)
                .single();
    }

    private String singleString(String sql) {
        return jdbc.sql(sql)
                .query(String.class)
                .single();
    }
}
