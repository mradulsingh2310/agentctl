package io.agentctl.api.workflow;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class JdbcRunProjectionActivities implements RunProjectionActivities {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcRunProjectionActivities(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void markRunRunning(RunWorkflowInput input) {
        Instant now = Instant.now();
        updateRunStatus(input.tenantId(), input.runId(), "RUNNING", now);
        insertAuditEvent(input.tenantId(), input.runId(), "RUN_STARTED", "Run started by Temporal", now);
    }

    @Override
    public void recordAgentStep(AgentStepRequest request, AgentStepResponse response) {
        Instant now = Instant.now();
        if (agentStepExists(request.tenantId(), request.stepId())) {
            return;
        }
        jdbc.sql("""
                        insert into agent_steps
                        (tenant_id, id, run_id, step_type, status, summary, input, output_json,
                         error_code, error_message, created_at, updated_at)
                        values (:tenant_id, :id, :run_id, :step_type, :status, :summary, :input, :output_json,
                                :error_code, :error_message, :created_at, :updated_at)
                        """)
                .param("tenant_id", request.tenantId())
                .param("id", request.stepId())
                .param("run_id", request.runId())
                .param("step_type", request.stepType())
                .param("status", response.status())
                .param("summary", response.summary())
                .param("input", request.input())
                .param("output_json", writeJson(response.output() == null ? Map.of() : response.output()))
                .param("error_code", response.error() == null ? null : response.error().code())
                .param("error_message", response.error() == null ? null : response.error().message())
                .param("created_at", Timestamp.from(now))
                .param("updated_at", Timestamp.from(now))
                .update();

        if (response.modelUsage() != null) {
            insertModelCall(request, response.modelUsage(), now);
        }
        for (AgentStepToolCall toolCall : toolCalls(response)) {
            insertToolCall(request, toolCall, now);
        }
        insertAuditEvent(request.tenantId(), request.runId(), "AGENT_STEP_RECORDED", "Agent step " + response.status(), now);
    }

    @Override
    public ApprovalRequestResult requestApproval(ApprovalRequest request) {
        Instant now = Instant.now();
        String approvalId = findApprovalId(request.tenantId(), request.runId())
                .orElseGet(() -> insertApproval(request, now));

        updateRunStatus(request.tenantId(), request.runId(), "WAITING_FOR_APPROVAL", now);
        insertAuditEvent(
                request.tenantId(),
                request.runId(),
                "APPROVAL_REQUESTED",
                "Approval requested for " + request.toolName(),
                now);
        return new ApprovalRequestResult(approvalId);
    }

    @Override
    public void completeRun(RunCompletion completion) {
        Instant now = Instant.now();
        if (completion.approvalId() != null) {
            updateApproval(
                    completion.tenantId(),
                    completion.approvalId(),
                    "APPROVED",
                    completion.actorId(),
                    completion.reason(),
                    now);
        }
        updateRunStatus(completion.tenantId(), completion.runId(), "COMPLETED", now);
        insertAuditEvent(completion.tenantId(), completion.runId(), "RUN_COMPLETED", "Run completed", now);
    }

    @Override
    public void rejectRun(RunRejection rejection) {
        Instant now = Instant.now();
        updateApproval(
                rejection.tenantId(),
                rejection.approvalId(),
                "REJECTED",
                rejection.actorId(),
                rejection.reason(),
                now);
        updateRunStatus(rejection.tenantId(), rejection.runId(), "REJECTED", now);
        insertAuditEvent(rejection.tenantId(), rejection.runId(), "RUN_REJECTED", "Run rejected", now);
    }

    @Override
    public void failRun(RunFailure failure) {
        Instant now = Instant.now();
        updateRunStatus(failure.tenantId(), failure.runId(), "FAILED", now);
        insertAuditEvent(failure.tenantId(), failure.runId(), "RUN_FAILED", failure.errorCode(), now);
    }

    private void insertModelCall(AgentStepRequest request, AgentStepModelUsage modelUsage, Instant createdAt) {
        jdbc.sql("""
                        insert into model_calls
                        (tenant_id, id, run_id, step_id, provider, model, input_tokens, output_tokens,
                         cost_estimate, created_at)
                        values (:tenant_id, :id, :run_id, :step_id, :provider, :model, :input_tokens, :output_tokens,
                                :cost_estimate, :created_at)
                        """)
                .param("tenant_id", request.tenantId())
                .param("id", prefixedId("model"))
                .param("run_id", request.runId())
                .param("step_id", request.stepId())
                .param("provider", modelUsage.provider())
                .param("model", modelUsage.model())
                .param("input_tokens", modelUsage.inputTokens())
                .param("output_tokens", modelUsage.outputTokens())
                .param("cost_estimate", null)
                .param("created_at", Timestamp.from(createdAt))
                .update();
    }

    private void insertToolCall(AgentStepRequest request, AgentStepToolCall toolCall, Instant createdAt) {
        jdbc.sql("""
                        insert into tool_calls
                        (tenant_id, id, run_id, step_id, tool_name, operation_id, status, backend, external_url,
                         fga_decision_id, metadata_json, created_at, updated_at)
                        values (:tenant_id, :id, :run_id, :step_id, :tool_name, :operation_id, :status, :backend,
                                :external_url, :fga_decision_id, :metadata_json, :created_at, :updated_at)
                        """)
                .param("tenant_id", request.tenantId())
                .param("id", prefixedId("tool"))
                .param("run_id", request.runId())
                .param("step_id", request.stepId())
                .param("tool_name", toolCall.toolName())
                .param("operation_id", toolCall.operationId())
                .param("status", toolCall.status())
                .param("backend", toolCall.backend())
                .param("external_url", toolCall.externalUrl())
                .param("fga_decision_id", toolCall.fgaDecisionId())
                .param("metadata_json", writeJson(toolCall.metadata() == null ? Map.of() : toolCall.metadata()))
                .param("created_at", Timestamp.from(createdAt))
                .param("updated_at", Timestamp.from(createdAt))
                .update();
    }

    private void updateApproval(
            String tenantId,
            String approvalId,
            String status,
            String actorId,
            String reason,
            Instant decidedAt) {
        int updated = jdbc.sql("""
                        update approvals
                        set status = :status,
                            decided_at = :decided_at,
                            decided_by = :decided_by,
                            decision_reason = :decision_reason
                        where tenant_id = :tenant_id and id = :id
                        """)
                .param("status", status)
                .param("decided_at", Timestamp.from(decidedAt))
                .param("decided_by", actorId)
                .param("decision_reason", reason)
                .param("tenant_id", tenantId)
                .param("id", approvalId)
                .update();
        if (updated == 0) {
            throw new IllegalStateException("Approval projection not found: " + approvalId);
        }
    }

    private void updateRunStatus(String tenantId, String runId, String status, Instant updatedAt) {
        int updated = jdbc.sql("""
                        update runs
                        set status = :status,
                            updated_at = :updated_at
                        where tenant_id = :tenant_id and id = :id
                        """)
                .param("status", status)
                .param("updated_at", Timestamp.from(updatedAt))
                .param("tenant_id", tenantId)
                .param("id", runId)
                .update();
        if (updated == 0) {
            throw new IllegalStateException("Run projection not found: " + runId);
        }
    }

    private void insertAuditEvent(String tenantId, String runId, String eventType, String message, Instant createdAt) {
        if (auditEventExists(tenantId, runId, eventType)) {
            return;
        }
        jdbc.sql("""
                        insert into audit_events (tenant_id, id, run_id, event_type, message, created_at)
                        values (:tenant_id, :id, :run_id, :event_type, :message, :created_at)
                        """)
                .param("tenant_id", tenantId)
                .param("id", prefixedId("audit"))
                .param("run_id", runId)
                .param("event_type", eventType)
                .param("message", message)
                .param("created_at", Timestamp.from(createdAt))
                .update();
    }

    private Optional<String> findApprovalId(String tenantId, String runId) {
        return jdbc.sql("""
                        select id
                        from approvals
                        where tenant_id = :tenant_id and run_id = :run_id
                        order by created_at asc, id asc
                        limit 1
                        """)
                .param("tenant_id", tenantId)
                .param("run_id", runId)
                .query(String.class)
                .optional();
    }

    private String insertApproval(ApprovalRequest request, Instant createdAt) {
        String approvalId = "approval_" + request.runId();
        jdbc.sql("""
                        insert into approvals
                        (tenant_id, id, run_id, status, tool_name, question, created_at)
                        values (:tenant_id, :id, :run_id, :status, :tool_name, :question, :created_at)
                        """)
                .param("tenant_id", request.tenantId())
                .param("id", approvalId)
                .param("run_id", request.runId())
                .param("status", "PENDING")
                .param("tool_name", request.toolName())
                .param("question", request.question())
                .param("created_at", Timestamp.from(createdAt))
                .update();
        return approvalId;
    }

    private boolean auditEventExists(String tenantId, String runId, String eventType) {
        return jdbc.sql("""
                        select count(*)
                        from audit_events
                        where tenant_id = :tenant_id
                          and run_id = :run_id
                          and event_type = :event_type
                        """)
                .param("tenant_id", tenantId)
                .param("run_id", runId)
                .param("event_type", eventType)
                .query((rs, rowNum) -> rs.getInt(1) > 0)
                .single();
    }

    private boolean agentStepExists(String tenantId, String stepId) {
        return jdbc.sql("""
                        select count(*)
                        from agent_steps
                        where tenant_id = :tenant_id and id = :id
                        """)
                .param("tenant_id", tenantId)
                .param("id", stepId)
                .query((rs, rowNum) -> rs.getInt(1) > 0)
                .single();
    }

    private static List<AgentStepToolCall> toolCalls(AgentStepResponse response) {
        if (response.toolCalls() == null) {
            return List.of();
        }
        return response.toolCalls();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize projection JSON", e);
        }
    }

    private static String prefixedId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
