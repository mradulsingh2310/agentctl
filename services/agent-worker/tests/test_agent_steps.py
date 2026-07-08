import unittest
import warnings
import json
import sqlite3
from pathlib import Path

from starlette.exceptions import StarletteDeprecationWarning

warnings.filterwarnings("ignore", category=StarletteDeprecationWarning)

from fastapi.testclient import TestClient

from agentctl_agent_worker.app import app
from agentctl_agent_worker.fake_ticketing import FakeTicketTool, LocalToolAuthorizer, initialize_sqlite_tool_schema

FIXTURE_ROOT = Path(__file__).resolve().parents[3] / "contracts" / "agent-step"


def load_fixture(name: str) -> dict:
    return json.loads((FIXTURE_ROOT / name).read_text())


class AgentStepApiTest(unittest.TestCase):
    def setUp(self) -> None:
        self.connection = sqlite3.connect(":memory:", check_same_thread=False)
        self.connection.row_factory = sqlite3.Row
        initialize_sqlite_tool_schema(self.connection)
        self.connection.execute(
            "insert into tenants (id, display_name, created_at) values (?, ?, ?)",
            ("tenant_a", "tenant_a", "2026-07-08T00:00:00+00:00"),
        )
        app.state.fake_ticket_tool = FakeTicketTool(self.connection, LocalToolAuthorizer())
        self.client = TestClient(app)

    def tearDown(self) -> None:
        self.connection.close()

    def test_health_endpoint_reports_http_runtime(self) -> None:
        response = self.client.get("/health")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            response.json(),
            {
                "service": "agentctl-agent-worker",
                "status": "UP",
                "agent_runtime": "langgraph",
                "model_boundary": "langchain",
            },
        )

    def test_support_ticket_step_returns_draft_and_approval_request(self) -> None:
        request = load_fixture("support-ticket-draft-request.json")
        response = self.client.post(
            "/v1/agent-steps",
            headers={
                "X-Agentctl-Tenant": request["tenantId"],
                "X-Agentctl-Run-Id": request["runId"],
                "X-Agentctl-Correlation-Id": request["traceContext"]["correlationId"],
            },
            json=request,
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), load_fixture("support-ticket-draft-response.json"))

    def test_support_ticket_execute_step_returns_fake_ticket_fixture(self) -> None:
        request = load_fixture("support-ticket-execute-fake-request.json")
        response = self.client.post(
            "/v1/agent-steps",
            headers={
                "X-Agentctl-Tenant": request["tenantId"],
                "X-Agentctl-Run-Id": request["runId"],
                "X-Agentctl-Correlation-Id": "corr_123",
            },
            json=request,
        )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), load_fixture("support-ticket-execute-fake-response.json"))
        self.assertEqual(self.count("fake_tickets"), 1)

    def test_rejects_header_body_tenant_mismatch(self) -> None:
        response = self.client.post(
            "/v1/agent-steps",
            headers={
                "X-Agentctl-Tenant": "tenant_b",
                "X-Agentctl-Run-Id": "run_123",
                "X-Agentctl-Correlation-Id": "corr_123",
            },
            json={
                "protocolVersion": "2026-07-07",
                "tenantId": "tenant_a",
                "runId": "run_123",
                "agentId": "support-ticket",
                "stepId": "step_001",
                "stepType": "draft_ticket",
                "input": "Create a support ticket",
                "modelProfile": {"provider": "stub", "model": "stub"},
                "toolContext": {},
                "traceContext": {"correlationId": "corr_123"},
            },
        )

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "Tenant header does not match request body")

    def test_unknown_agent_returns_structured_failure(self) -> None:
        response = self.client.post(
            "/v1/agent-steps",
            headers={
                "X-Agentctl-Tenant": "tenant_a",
                "X-Agentctl-Run-Id": "run_123",
                "X-Agentctl-Correlation-Id": "corr_123",
            },
            json={
                "protocolVersion": "2026-07-07",
                "tenantId": "tenant_a",
                "runId": "run_123",
                "agentId": "unknown-agent",
                "stepId": "step_001",
                "stepType": "draft_ticket",
                "input": "Create a support ticket",
                "modelProfile": {"provider": "stub", "model": "stub"},
                "toolContext": {},
                "traceContext": {"correlationId": "corr_123"},
            },
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["status"], "FAILED")
        self.assertEqual(body["error"]["code"], "UNSUPPORTED_AGENT")
        self.assertFalse(body["error"]["retryable"])

    def count(self, table: str) -> int:
        return self.connection.execute(f"select count(*) from {table}").fetchone()[0]


if __name__ == "__main__":
    unittest.main()
