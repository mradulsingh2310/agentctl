import json

from agentctl_agent_worker.health import health


def main() -> None:
    print(json.dumps(health(), sort_keys=True))
