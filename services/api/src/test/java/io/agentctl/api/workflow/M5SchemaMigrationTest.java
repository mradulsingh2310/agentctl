package io.agentctl.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agentctl-m5-schema;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "agentctl.temporal.enabled=false"
})
class M5SchemaMigrationTest {
    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void seedRun() {
        jdbc.sql("delete from fake_ticket_events").update();
        jdbc.sql("delete from tool_idempotency_records").update();
        jdbc.sql("delete from tickets").update();
        jdbc.sql("delete from fake_tickets").update();
        jdbc.sql("delete from tool_calls").update();
        jdbc.sql("delete from model_calls").update();
        jdbc.sql("delete from agent_steps").update();
        jdbc.sql("delete from audit_events").update();
        jdbc.sql("delete from approvals").update();
        jdbc.sql("delete from runs").update();
        jdbc.sql("delete from users").update();
        jdbc.sql("delete from tenants").update();

        Instant now = Instant.now();
        jdbc.sql("""
                        insert into tenants (id, display_name, created_at)
                        values (:id, :display_name, :created_at)
                        """)
                .param("id", "tenant_m5")
                .param("display_name", "tenant_m5")
                .param("created_at", Timestamp.from(now))
                .update();
        jdbc.sql("""
                        insert into runs (tenant_id, id, agent_id, status, input, created_at, updated_at)
                        values (:tenant_id, :id, :agent_id, :status, :input, :created_at, :updated_at)
                        """)
                .param("tenant_id", "tenant_m5")
                .param("id", "run_m5_schema")
                .param("agent_id", "support-ticket")
                .param("status", "RUNNING")
                .param("input", "Create a support ticket")
                .param("created_at", Timestamp.from(now))
                .param("updated_at", Timestamp.from(now))
                .update();
    }

    @Test
    void createsTicketProjectionAndFakeBackendTables() {
        Instant now = Instant.now();
        jdbc.sql("""
                        insert into tickets
                        (tenant_id, id, run_id, backend, external_ticket_id, external_url, title, body, status,
                         severity, labels_json, assignee, idempotency_marker, created_at, updated_at)
                        values (:tenant_id, :id, :run_id, :backend, :external_ticket_id, :external_url, :title,
                                :body, :status, :severity, :labels_json, :assignee, :idempotency_marker,
                                :created_at, :updated_at)
                        """)
                .param("tenant_id", "tenant_m5")
                .param("id", "ticket_run_m5_schema")
                .param("run_id", "run_m5_schema")
                .param("backend", "fake")
                .param("external_ticket_id", "fake_run_m5_schema")
                .param("external_url", null)
                .param("title", "Checkout fails with HTTP 500")
                .param("body", "User reported checkout failing with HTTP 500.")
                .param("status", "OPEN")
                .param("severity", "high")
                .param("labels_json", "[\"bug\",\"checkout\"]")
                .param("assignee", null)
                .param("idempotency_marker", "agentctl:run_m5_schema:approval_run_m5_schema:fake_ticket.create")
                .param("created_at", Timestamp.from(now))
                .param("updated_at", Timestamp.from(now))
                .update();
        jdbc.sql("""
                        insert into fake_tickets
                        (tenant_id, id, title, body, status, severity, labels_json, assignee,
                         idempotency_marker, created_at, updated_at)
                        values (:tenant_id, :id, :title, :body, :status, :severity, :labels_json, :assignee,
                                :idempotency_marker, :created_at, :updated_at)
                        """)
                .param("tenant_id", "tenant_m5")
                .param("id", "fake_run_m5_schema")
                .param("title", "Checkout fails with HTTP 500")
                .param("body", "User reported checkout failing with HTTP 500.")
                .param("status", "OPEN")
                .param("severity", "high")
                .param("labels_json", "[\"bug\",\"checkout\"]")
                .param("assignee", null)
                .param("idempotency_marker", "agentctl:run_m5_schema:approval_run_m5_schema:fake_ticket.create")
                .param("created_at", Timestamp.from(now))
                .param("updated_at", Timestamp.from(now))
                .update();
        jdbc.sql("""
                        insert into fake_ticket_events
                        (tenant_id, id, fake_ticket_id, event_type, event_json, created_at)
                        values (:tenant_id, :id, :fake_ticket_id, :event_type, :event_json, :created_at)
                        """)
                .param("tenant_id", "tenant_m5")
                .param("id", "fake_event_run_m5_schema")
                .param("fake_ticket_id", "fake_run_m5_schema")
                .param("event_type", "CREATED")
                .param("event_json", "{\"operationId\":\"run_m5_schema:approval_run_m5_schema:fake_ticket.create\"}")
                .param("created_at", Timestamp.from(now))
                .update();

        assertThat(count("tickets")).isEqualTo(1);
        assertThat(count("fake_tickets")).isEqualTo(1);
        assertThat(count("fake_ticket_events")).isEqualTo(1);
    }

    @Test
    void createsToolIdempotencyRecordsTable() {
        Instant now = Instant.now();
        jdbc.sql("""
                        insert into tool_idempotency_records
                        (tenant_id, operation_id, tool_name, status, request_hash, response_json, created_at, updated_at)
                        values (:tenant_id, :operation_id, :tool_name, :status, :request_hash, :response_json,
                                :created_at, :updated_at)
                        """)
                .param("tenant_id", "tenant_m5")
                .param("operation_id", "run_m5_schema:approval_run_m5_schema:fake_ticket.create")
                .param("tool_name", "fake_ticket.create")
                .param("status", "COMPLETED")
                .param("request_hash", "sha256:test")
                .param("response_json", "{\"externalTicketId\":\"fake_run_m5_schema\"}")
                .param("created_at", Timestamp.from(now))
                .param("updated_at", Timestamp.from(now))
                .update();

        assertThat(count("tool_idempotency_records")).isEqualTo(1);
    }

    private Integer count(String table) {
        return jdbc.sql("select count(*) from " + table)
                .query(Integer.class)
                .single();
    }
}
