import unittest
from io import StringIO
from unittest.mock import patch

from agentctl_agent_worker.health import health
from agentctl_agent_worker.main import main


class HealthTest(unittest.TestCase):
    def test_reports_worker_contract(self) -> None:
        self.assertEqual(
            health(),
            {
                "service": "agentctl-agent-worker",
                "status": "UP",
                "agent_runtime": "langgraph",
                "model_boundary": "langchain",
            },
        )

    def test_health_command_prints_status(self) -> None:
        output = StringIO()

        with patch("sys.stdout", output):
            main(["--health"])

        self.assertIn('"service": "agentctl-agent-worker"', output.getvalue())


if __name__ == "__main__":
    unittest.main()
