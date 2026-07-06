import unittest

from agentctl_agent_worker.health import health


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


if __name__ == "__main__":
    unittest.main()
