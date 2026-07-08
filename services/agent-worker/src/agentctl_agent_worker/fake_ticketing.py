import hashlib
import json
import os
import re
import sqlite3
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any
from urllib.parse import quote


@dataclass(frozen=True)
class ToolExecutionContext:
    tenant_id: str
    run_id: str
    backend: str
    approval: dict[str, Any]
    draft: dict[str, Any]
    operation_base_id: str


@dataclass(frozen=True)
class ToolAuthorizationDecision:
    decision_id: str
    allowed: bool
    reason: str


@dataclass(frozen=True)
class ToolExecutionResult:
    status: str
    ticket: dict[str, Any]
    tool_call: dict[str, Any]
    error: dict[str, Any] | None = None


class LocalToolAuthorizer:
    def authorize(self, context: ToolExecutionContext, tool_name: str) -> ToolAuthorizationDecision:
        if context.backend == "fake":
            return ToolAuthorizationDecision("local-dev", True, "Local fake backend is allowed.")
        return ToolAuthorizationDecision("local-dev", False, f"Unsupported backend for {tool_name}.")


class FakeTicketTool:
    def __init__(self, connection: Any, authorizer: LocalToolAuthorizer, dialect: str = "sqlite") -> None:
        self.connection = connection
        self.authorizer = authorizer
        self.dialect = dialect

    def create_ticket(self, context: ToolExecutionContext) -> ToolExecutionResult:
        operation_id = f"{context.operation_base_id}:fake_ticket.create"
        tool_name = "fake_ticket.create"
        decision = self.authorizer.authorize(context, tool_name)
        tool_call = {
            "toolName": tool_name,
            "operationId": operation_id,
            "status": "COMPLETED" if decision.allowed else "FAILED",
            "backend": context.backend,
            "externalUrl": None,
            "fgaDecisionId": decision.decision_id,
            "metadata": {},
        }
        if not decision.allowed:
            return ToolExecutionResult(
                status="FAILED",
                ticket={},
                tool_call=tool_call,
                error={
                    "code": "TOOL_AUTHZ_DENIED",
                    "message": decision.reason,
                    "retryable": False,
                },
            )

        request_hash = sha256_json({
            "tenantId": context.tenant_id,
            "operationId": operation_id,
            "toolName": tool_name,
            "draft": context.draft,
        })
        existing = self._find_idempotency_record(context.tenant_id, operation_id)
        if existing is not None:
            status = existing["status"]
            if status == "COMPLETED":
                return ToolExecutionResult(**json.loads(existing["response_json"]))
            return ToolExecutionResult(
                status="FAILED",
                ticket={},
                tool_call={**tool_call, "status": "FAILED"},
                error={
                    "code": "TOOL_IDEMPOTENCY_CONFLICT" if status == "IN_PROGRESS" else "TOOL_IDEMPOTENCY_FAILED",
                    "message": f"Operation {operation_id} is already {status}.",
                    "retryable": False,
                },
            )

        now = utc_now()
        ticket = ticket_response(context, operation_id)
        tool_call = {
            **tool_call,
            "metadata": {"externalTicketId": ticket["externalTicketId"]},
        }
        result = ToolExecutionResult(
            status="COMPLETED",
            ticket=ticket,
            tool_call=tool_call,
        )
        response_json = json.dumps(result.__dict__, sort_keys=True, separators=(",", ":"))

        try:
            self._execute(
                """
                insert into tool_idempotency_records
                (tenant_id, operation_id, tool_name, status, request_hash, response_json, created_at, updated_at)
                values (:tenant_id, :operation_id, :tool_name, :status, :request_hash, :response_json,
                        :created_at, :updated_at)
                """,
                {
                    "tenant_id": context.tenant_id,
                    "operation_id": operation_id,
                    "tool_name": tool_name,
                    "status": "IN_PROGRESS",
                    "request_hash": request_hash,
                    "response_json": None,
                    "created_at": now,
                    "updated_at": now,
                },
            )
            self._execute(
                """
                insert into fake_tickets
                (tenant_id, id, title, body, status, severity, labels_json, assignee,
                 idempotency_marker, created_at, updated_at)
                values (:tenant_id, :id, :title, :body, :status, :severity, :labels_json, :assignee,
                        :idempotency_marker, :created_at, :updated_at)
                """,
                {
                    "tenant_id": context.tenant_id,
                    "id": ticket["externalTicketId"],
                    "title": ticket["title"],
                    "body": ticket["body"],
                    "status": ticket["status"],
                    "severity": ticket["severity"],
                    "labels_json": json.dumps(ticket["labels"], sort_keys=True),
                    "assignee": ticket["assignee"],
                    "idempotency_marker": ticket["idempotencyMarker"],
                    "created_at": now,
                    "updated_at": now,
                },
            )
            self._execute(
                """
                insert into fake_ticket_events
                (tenant_id, id, fake_ticket_id, event_type, event_json, created_at)
                values (:tenant_id, :id, :fake_ticket_id, :event_type, :event_json, :created_at)
                """,
                {
                    "tenant_id": context.tenant_id,
                    "id": "fake_event_" + hashlib.sha256(operation_id.encode()).hexdigest()[:16],
                    "fake_ticket_id": ticket["externalTicketId"],
                    "event_type": "CREATED",
                    "event_json": json.dumps({"operationId": operation_id}, sort_keys=True),
                    "created_at": now,
                },
            )
            self._execute(
                """
                update tool_idempotency_records
                set status = :status, response_json = :response_json, updated_at = :updated_at
                where tenant_id = :tenant_id and operation_id = :operation_id
                """,
                {
                    "status": "COMPLETED",
                    "response_json": response_json,
                    "updated_at": now,
                    "tenant_id": context.tenant_id,
                    "operation_id": operation_id,
                },
            )
            self.connection.commit()
        except Exception:
            self.connection.rollback()
            raise
        return result

    def _find_idempotency_record(self, tenant_id: str, operation_id: str) -> Any | None:
        return self._execute(
            """
            select status, response_json
            from tool_idempotency_records
            where tenant_id = :tenant_id and operation_id = :operation_id
            """,
            {"tenant_id": tenant_id, "operation_id": operation_id},
        ).fetchone()

    def _execute(self, sql: str, params: dict[str, Any]):
        return self.connection.execute(render_sql(sql, self.dialect), params)


def ticket_response(context: ToolExecutionContext, operation_id: str) -> dict[str, Any]:
    draft = context.draft
    return {
        "backend": "fake",
        "externalTicketId": f"fake_{context.run_id}",
        "externalUrl": None,
        "title": draft["title"],
        "body": draft["body"],
        "status": "OPEN",
        "severity": draft["severity"],
        "labels": draft.get("labels", []),
        "assignee": draft.get("assignee"),
        "idempotencyMarker": f"agentctl:{operation_id}",
    }


def sha256_json(payload: dict[str, Any]) -> str:
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


def utc_now() -> str:
    return datetime.now(UTC).isoformat()


def render_sql(sql: str, dialect: str) -> str:
    if dialect == "sqlite":
        return sql
    if dialect == "postgres":
        return re.sub(r":([A-Za-z_][A-Za-z0-9_]*)", r"%(\1)s", sql)
    raise ValueError(f"Unsupported SQL dialect: {dialect}")


def build_fake_ticket_tool_from_env(env: dict[str, str] | None = None) -> FakeTicketTool:
    config = env if env is not None else os.environ
    database_url = config.get("AGENTCTL_TOOL_DB_URL") or postgres_url_from_env(config)
    if database_url.startswith("postgresql://") or database_url.startswith("postgres://"):
        try:
            import psycopg
            from psycopg.rows import dict_row
        except ImportError as exc:
            raise RuntimeError("Postgres tool storage requires psycopg") from exc
        connection = psycopg.connect(database_url, row_factory=dict_row)
        return FakeTicketTool(connection, LocalToolAuthorizer(), dialect="postgres")

    connection = sqlite3.connect(":memory:", check_same_thread=False)
    connection.row_factory = sqlite3.Row
    initialize_sqlite_tool_schema(connection)
    return FakeTicketTool(connection, LocalToolAuthorizer(), dialect="sqlite")


def postgres_url_from_env(env: dict[str, str]) -> str:
    host = env.get("POSTGRES_HOST")
    if not host:
        return ""
    port = env.get("POSTGRES_PORT", "5432")
    database = env.get("POSTGRES_DB", "agentctl")
    user = env.get("POSTGRES_USER", "agentctl")
    password = env.get("POSTGRES_PASSWORD", "agentctl")
    return f"postgresql://{quote(user)}:{quote(password)}@{host}:{port}/{quote(database)}"


def initialize_sqlite_tool_schema(connection: sqlite3.Connection) -> None:
    connection.executescript(
        """
        create table if not exists tenants (
            id text primary key,
            display_name text not null,
            created_at text not null
        );

        create table if not exists fake_tickets (
            tenant_id text not null,
            id text not null,
            title text not null,
            body text not null,
            status text not null,
            severity text not null,
            labels_json text not null,
            assignee text,
            idempotency_marker text not null,
            created_at text not null,
            updated_at text not null,
            primary key (tenant_id, id)
        );

        create table if not exists fake_ticket_events (
            tenant_id text not null,
            id text not null,
            fake_ticket_id text not null,
            event_type text not null,
            event_json text not null,
            created_at text not null,
            primary key (tenant_id, id)
        );

        create table if not exists tool_idempotency_records (
            tenant_id text not null,
            operation_id text not null,
            tool_name text not null,
            status text not null,
            request_hash text not null,
            response_json text,
            created_at text not null,
            updated_at text not null,
            primary key (tenant_id, operation_id)
        );
        """
    )
