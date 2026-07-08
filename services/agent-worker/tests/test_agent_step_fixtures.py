import json
import unittest
from pathlib import Path

from agentctl_agent_worker.protocol import AgentStepRequest, AgentStepResponse

FIXTURE_ROOT = Path(__file__).resolve().parents[3] / "contracts" / "agent-step"


def load_fixture(name: str) -> dict:
    return json.loads((FIXTURE_ROOT / name).read_text())


class AgentStepFixtureContractTest(unittest.TestCase):
    def test_execute_fake_ticket_fixtures_match_agent_step_protocol(self) -> None:
        request = AgentStepRequest.model_validate(load_fixture("support-ticket-execute-fake-request.json"))
        response = AgentStepResponse.model_validate(load_fixture("support-ticket-execute-fake-response.json"))

        self.assertEqual(request.agentId, "support-ticket")
        self.assertEqual(request.stepType, "execute_ticket_workflow")
        self.assertEqual(request.toolContext["backend"], "fake")
        self.assertIn("approval", request.toolContext)
        self.assertIn("draft", request.toolContext)
        self.assertIn("operationBaseId", request.toolContext)
        self.assertEqual(response.status, "COMPLETED")
        self.assertEqual(response.output["ticket"]["backend"], "fake")
        self.assertEqual(response.output["ticket"]["externalTicketId"], "fake_run_123")
        self.assertEqual(len(response.toolCalls), 1)
        self.assertEqual(response.toolCalls[0]["toolName"], "fake_ticket.create")
        self.assertEqual(response.toolCalls[0]["operationId"], "run_123:approval_run_123:fake_ticket.create")


if __name__ == "__main__":
    unittest.main()
