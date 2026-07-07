package io.agentctl.api.controlplane;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import io.agentctl.api.workflow.ApprovalSignal;
import io.agentctl.api.workflow.RunWorkflowGateway;
import io.agentctl.api.workflow.RunWorkflowInput;

@Service
public class ControlPlaneService {
    private static final String RUN_CREATED = "RUN_CREATED";
    private static final String APPROVAL_APPROVED = "APPROVAL_APPROVED";
    private static final String APPROVAL_REJECTED = "APPROVAL_REJECTED";

    private final JdbcClient jdbc;
    private final RunWorkflowGateway runWorkflowGateway;

    public ControlPlaneService(JdbcClient jdbc, RunWorkflowGateway runWorkflowGateway) {
        this.jdbc = jdbc;
        this.runWorkflowGateway = runWorkflowGateway;
    }

    @Transactional
    public RunResponse createRun(String tenantId, CreateRunRequest request) {
        ensureTenant(tenantId);

        Instant now = Instant.now();
        String runId = prefixedId("run");
        jdbc.sql("""
                        insert into runs (tenant_id, id, agent_id, status, input, created_at, updated_at)
                        values (:tenant_id, :id, :agent_id, :status, :input, :created_at, :updated_at)
                        """)
                .param("tenant_id", tenantId)
                .param("id", runId)
                .param("agent_id", request.agentId())
                .param("status", "CREATED")
                .param("input", request.input())
                .param("created_at", Timestamp.from(now))
                .param("updated_at", Timestamp.from(now))
                .update();

        insertAuditEvent(tenantId, runId, RUN_CREATED, "Run created", now);
        runWorkflowGateway.startRun(new RunWorkflowInput(tenantId, runId, request.agentId(), request.input()));
        return getRun(tenantId, runId);
    }

    @Transactional(readOnly = true)
    public ItemListResponse<RunResponse> listRuns(String tenantId) {
        List<RunResponse> runs = jdbc.sql("""
                        select id, agent_id, status, input, created_at, updated_at
                        from runs
                        where tenant_id = :tenant_id
                        order by created_at desc, id desc
                        """)
                .param("tenant_id", tenantId)
                .query(ControlPlaneService::mapRun)
                .list();
        return new ItemListResponse<>(runs);
    }

    @Transactional(readOnly = true)
    public RunResponse getRun(String tenantId, String runId) {
        return findRun(tenantId, runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }

    @Transactional(readOnly = true)
    public ItemListResponse<ApprovalResponse> listPendingApprovals(String tenantId) {
        List<ApprovalResponse> approvals = jdbc.sql("""
                        select id, run_id, status, tool_name, question, created_at, decided_at, decided_by, decision_reason
                        from approvals
                        where tenant_id = :tenant_id and status = 'PENDING'
                        order by created_at asc, id asc
                        """)
                .param("tenant_id", tenantId)
                .query(ControlPlaneService::mapApproval)
                .list();
        return new ItemListResponse<>(approvals);
    }

    @Transactional
    public ApprovalResponse approveApproval(String tenantId, String approvalId, ApprovalDecisionRequest request) {
        return decideApproval(tenantId, approvalId, request, "APPROVED", APPROVAL_APPROVED);
    }

    @Transactional
    public ApprovalResponse rejectApproval(String tenantId, String approvalId, ApprovalDecisionRequest request) {
        return decideApproval(tenantId, approvalId, request, "REJECTED", APPROVAL_REJECTED);
    }

    @Transactional(readOnly = true)
    public ItemListResponse<AuditEventResponse> listRunAuditEvents(String tenantId, String runId) {
        getRun(tenantId, runId);
        List<AuditEventResponse> events = jdbc.sql("""
                        select id, run_id, event_type, message, created_at
                        from audit_events
                        where tenant_id = :tenant_id and run_id = :run_id
                        order by created_at asc, id asc
                        """)
                .param("tenant_id", tenantId)
                .param("run_id", runId)
                .query(ControlPlaneService::mapAuditEvent)
                .list();
        return new ItemListResponse<>(events);
    }

    private ApprovalResponse decideApproval(
            String tenantId,
            String approvalId,
            ApprovalDecisionRequest request,
            String nextStatus,
            String auditEventType) {
        ApprovalResponse approval = findApproval(tenantId, approvalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval not found"));
        if (!"PENDING".equals(approval.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval is not pending");
        }

        Instant now = Instant.now();
        jdbc.sql("""
                        update approvals
                        set status = :status,
                            decided_at = :decided_at,
                            decided_by = :decided_by,
                            decision_reason = :decision_reason
                        where tenant_id = :tenant_id and id = :id
                        """)
                .param("status", nextStatus)
                .param("decided_at", Timestamp.from(now))
                .param("decided_by", request.actorId())
                .param("decision_reason", request.reason())
                .param("tenant_id", tenantId)
                .param("id", approvalId)
                .update();

        insertAuditEvent(tenantId, approval.runId(), auditEventType, "Approval " + nextStatus.toLowerCase(), now);
        runWorkflowGateway.signalApproval(approval.runId(), new ApprovalSignal(
                approval.runId(),
                approvalId,
                nextStatus,
                request.actorId(),
                request.reason()));
        return findApproval(tenantId, approvalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval not found"));
    }

    private void ensureTenant(String tenantId) {
        boolean exists = jdbc.sql("select count(*) from tenants where id = :id")
                .param("id", tenantId)
                .query((rs, rowNum) -> rs.getInt(1) > 0)
                .single();
        if (exists) {
            return;
        }

        Instant now = Instant.now();
        jdbc.sql("""
                        insert into tenants (id, display_name, created_at)
                        values (:id, :display_name, :created_at)
                        """)
                .param("id", tenantId)
                .param("display_name", tenantId)
                .param("created_at", Timestamp.from(now))
                .update();
    }

    private Optional<RunResponse> findRun(String tenantId, String runId) {
        return jdbc.sql("""
                        select id, agent_id, status, input, created_at, updated_at
                        from runs
                        where tenant_id = :tenant_id and id = :id
                        """)
                .param("tenant_id", tenantId)
                .param("id", runId)
                .query(ControlPlaneService::mapRun)
                .optional();
    }

    private Optional<ApprovalResponse> findApproval(String tenantId, String approvalId) {
        return jdbc.sql("""
                        select id, run_id, status, tool_name, question, created_at, decided_at, decided_by, decision_reason
                        from approvals
                        where tenant_id = :tenant_id and id = :id
                        """)
                .param("tenant_id", tenantId)
                .param("id", approvalId)
                .query(ControlPlaneService::mapApproval)
                .optional();
    }

    private void insertAuditEvent(String tenantId, String runId, String eventType, String message, Instant createdAt) {
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

    private static RunResponse mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new RunResponse(
                rs.getString("id"),
                rs.getString("agent_id"),
                rs.getString("status"),
                rs.getString("input"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static ApprovalResponse mapApproval(ResultSet rs, int rowNum) throws SQLException {
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new ApprovalResponse(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("status"),
                rs.getString("tool_name"),
                rs.getString("question"),
                rs.getTimestamp("created_at").toInstant(),
                decidedAt == null ? null : decidedAt.toInstant(),
                rs.getString("decided_by"),
                rs.getString("decision_reason"));
    }

    private static AuditEventResponse mapAuditEvent(ResultSet rs, int rowNum) throws SQLException {
        return new AuditEventResponse(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("event_type"),
                rs.getString("message"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static String prefixedId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
