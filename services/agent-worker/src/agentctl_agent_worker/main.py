import argparse
import json

import uvicorn

from agentctl_agent_worker.health import health


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="agentctl-agent-worker")
    parser.add_argument("--health", action="store_true")
    args = parser.parse_args(argv)

    if args.health:
        print(json.dumps(health(), sort_keys=True))
        return

    uvicorn.run(
        "agentctl_agent_worker.app:app",
        host="0.0.0.0",
        port=8090,
    )
