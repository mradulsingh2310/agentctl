import sqlite3
import unittest

from agentctl_agent_worker.fake_ticketing import (
    FakeTicketTool,
    LocalToolAuthorizer,
    ToolExecutionContext,
    postgres_url_from_env,
    initialize_sqlite_tool_schema,
    render_sql,
)


class FakeTicketToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.connection = sqlite3.connect(":memory:")
        self.connection.row_factory = sqlite3.Row
        initialize_sqlite_tool_schema(self.connection)
        self.connection.execute(
            "insert into tenants (id, display_name, created_at) values (?, ?, ?)",
            ("tenant_a", "tenant_a", "2026-07-08T00:00:00+00:00"),
        )
        self.tool = FakeTicketTool(self.connection, LocalToolAuthorizer())

    def tearDown(self) -> None:
        self.connection.close()

    def test_create_ticket_persists_fake_ticket_event_and_idempotency_record(self) -> None:
        result = self.tool.create_ticket(approved_context())

        self.assertEqual(result.status, "COMPLETED")
        self.assertEqual(result.ticket["externalTicketId"], "fake_run_123")
        self.assertEqual(result.ticket["idempotencyMarker"], "agentctl:run_123:approval_run_123:fake_ticket.create")
        self.assertEqual(result.tool_call["toolName"], "fake_ticket.create")
        self.assertEqual(result.tool_call["operationId"], "run_123:approval_run_123:fake_ticket.create")
        self.assertEqual(self.count("fake_tickets"), 1)
        self.assertEqual(self.count("fake_ticket_events"), 1)
        self.assertEqual(self.count("tool_idempotency_records"), 1)
        self.assertEqual(
            self.scalar("select status from tool_idempotency_records where operation_id = ?",
                        ("run_123:approval_run_123:fake_ticket.create",)),
            "COMPLETED",
        )

    def test_create_ticket_replays_completed_idempotency_record_without_duplicate_mutation(self) -> None:
        first = self.tool.create_ticket(approved_context())
        replay = self.tool.create_ticket(approved_context())

        self.assertEqual(replay.status, "COMPLETED")
        self.assertEqual(replay.ticket, first.ticket)
        self.assertEqual(replay.tool_call, first.tool_call)
        self.assertEqual(self.count("fake_tickets"), 1)
        self.assertEqual(self.count("fake_ticket_events"), 1)
        self.assertEqual(self.count("tool_idempotency_records"), 1)

    def test_create_ticket_returns_conflict_when_operation_is_in_progress(self) -> None:
        self.connection.execute(
            """
            insert into tool_idempotency_records
            (tenant_id, operation_id, tool_name, status, request_hash, response_json, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                "tenant_a",
                "run_123:approval_run_123:fake_ticket.create",
                "fake_ticket.create",
                "IN_PROGRESS",
                "sha256:existing",
                None,
                "2026-07-08T00:00:00+00:00",
                "2026-07-08T00:00:00+00:00",
            ),
        )

        result = self.tool.create_ticket(approved_context())

        self.assertEqual(result.status, "FAILED")
        self.assertEqual(result.error["code"], "TOOL_IDEMPOTENCY_CONFLICT")
        self.assertFalse(result.error["retryable"])
        self.assertEqual(self.count("fake_tickets"), 0)
        self.assertEqual(self.count("fake_ticket_events"), 0)

    def test_render_sql_converts_named_parameters_for_postgres(self) -> None:
        sql = "select * from fake_tickets where tenant_id = :tenant_id and id = :id"

        self.assertEqual(
            render_sql(sql, "postgres"),
            "select * from fake_tickets where tenant_id = %(tenant_id)s and id = %(id)s",
        )

    def test_postgres_url_from_env_uses_compose_database_settings(self) -> None:
        self.assertEqual(
            postgres_url_from_env({
                "POSTGRES_HOST": "postgres",
                "POSTGRES_PORT": "5432",
                "POSTGRES_DB": "agentctl",
                "POSTGRES_USER": "agentctl",
                "POSTGRES_PASSWORD": "agentctl",
            }),
            "postgresql://agentctl:agentctl@postgres:5432/agentctl",
        )

    def count(self, table: str) -> int:
        return self.connection.execute(f"select count(*) from {table}").fetchone()[0]

    def scalar(self, sql: str, params: tuple[str, ...]) -> str:
        return self.connection.execute(sql, params).fetchone()[0]


def approved_context() -> ToolExecutionContext:
    return ToolExecutionContext(
        tenant_id="tenant_a",
        run_id="run_123",
        backend="fake",
        approval={"approvalId": "approval_run_123", "actorId": "user_local", "reason": "Looks safe"},
        draft={
            "title": "Checkout fails with HTTP 500",
            "body": "User reported checkout failing with HTTP 500.",
            "severity": "high",
            "labels": ["bug", "checkout"],
            "assignee": None,
        },
        operation_base_id="run_123:approval_run_123",
    )


if __name__ == "__main__":
    unittest.main()
