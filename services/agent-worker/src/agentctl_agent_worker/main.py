import argparse
import json
import time

from agentctl_agent_worker.health import health


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="agentctl-agent-worker")
    parser.add_argument("--health", action="store_true")
    args = parser.parse_args(argv)

    if args.health:
        print(json.dumps(health(), sort_keys=True))
        return

    while True:
        time.sleep(60)
