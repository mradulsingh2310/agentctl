# agentctl

Self-hosted durable control plane for LangGraph agents.

`agentctl` is planned as a production-oriented OSS platform for running long-lived
agent workflows with Temporal-backed execution, approval gates, operational
visibility, model/cost telemetry, and eval gates.

## Current Status

This repository currently contains product requirements documents and the initial
OSS project shell. Implementation has not started yet.

## PRDs

- [Platform PRD](docs/prd/agentctl-platform.md)
- [Support Ticket Agent PRD](docs/prd/agents/support-ticket-agent.md)
- [Incident Agent PRD](docs/prd/agents/incident-agent.md)
- [GitHub Ops Ticket-to-PR Agent PRD](docs/prd/agents/github-ops-ticket-to-pr-agent.md)

## Planned Local Demo

The target first-run experience is:

```bash
docker compose up
```

The stack will start the Java/Spring control plane, Temporal, Postgres, MinIO,
Next.js dashboard, Python LangGraph agent worker, local Ollama model profile,
and OTel/Grafana observability services.

## License

Apache-2.0
