import json
import sqlite3
import unittest
from pathlib import Path

from agentctl_agent_worker.fake_ticketing import FakeTicketTool, LocalToolAuthorizer, initialize_sqlite_tool_schema
from agentctl_agent_worker.protocol import AgentStepResponse
from agentctl_agent_worker.support_ticket import handle_support_ticket_step

FIXTURE_ROOT = Path(__file__).resolve().parents[3] / "contracts" / "agent-step"


def load_fixture(name: str) -> dict:
    return json.loads((FIXTURE_ROOT / name).read_text())


class SupportTicketExecuteStepTest(unittest.TestCase):
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

    def test_execute_ticket_workflow_matches_shared_fake_fixture(self) -> None:
        request = load_fixture("support-ticket-execute-fake-request.json")

        response = handle_support_ticket_step(request, fake_ticket_tool=self.tool)

        self.assertEqual(response.model_dump(mode="json"), load_fixture("support-ticket-execute-fake-response.json"))

    def test_execute_ticket_workflow_requires_approval_context(self) -> None:
        request = load_fixture("support-ticket-execute-fake-request.json")
        request["toolContext"].pop("approval")

        response = handle_support_ticket_step(request, fake_ticket_tool=self.tool)

        self.assertEqual(response.status, "FAILED")
        self.assertEqual(response.error.code, "MISSING_APPROVAL_CONTEXT")
        self.assertFalse(response.error.retryable)
        self.assertEqual(self.count("fake_tickets"), 0)

    def test_unsupported_backend_returns_structured_failure_without_mutation(self) -> None:
        request = load_fixture("support-ticket-execute-fake-request.json")
        request["toolContext"]["backend"] = "github"

        response = handle_support_ticket_step(request, fake_ticket_tool=self.tool)

        self.assertIsInstance(response, AgentStepResponse)
        self.assertEqual(response.status, "FAILED")
        self.assertEqual(response.error.code, "UNSUPPORTED_TICKET_BACKEND")
        self.assertFalse(response.error.retryable)
        self.assertEqual(self.count("fake_tickets"), 0)

    def count(self, table: str) -> int:
        return self.connection.execute(f"select count(*) from {table}").fetchone()[0]


if __name__ == "__main__":
    unittest.main()
