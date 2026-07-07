# agentctl

Self-hosted durable control plane for LangGraph agents.

`agentctl` is planned as a production-oriented OSS platform for running long-lived
agent workflows with Temporal-backed execution, approval gates, operational
visibility, model/cost telemetry, and eval gates.

## Current Status

This repository contains the M1-M3 platform foundation:

- Java/Spring API with `/api/health`, run projections, approval projections,
  audit timeline APIs, Flyway migrations, and Postgres wiring
- Temporal Java run workflow with approval signals and projection activities
- Python `uv` agent-worker shell
- Next.js dashboard shell
- Docker Compose wiring with healthchecks for API, worker, web, Temporal,
  Postgres, and MinIO by default, plus opt-in agent and observability profiles

## PRDs

- [Platform PRD](docs/prd/agentctl-platform.md)
- [M1/M2 Foundation PRD](docs/prd/m1-m2-foundation.md)
- [Support Ticket Agent PRD](docs/prd/agents/support-ticket-agent.md)
- [Incident Agent PRD](docs/prd/agents/incident-agent.md)
- [GitHub Ops Ticket-to-PR Agent PRD](docs/prd/agents/github-ops-ticket-to-pr-agent.md)

## Local Demo

Copy `.env.example` if you want to override local defaults, then run:

```bash
docker compose up --build
```

The stack will start the Java/Spring control plane, Temporal, Postgres, MinIO,
Next.js dashboard, and Java Temporal worker.

Optional profiles:

```bash
docker compose --profile agents up --build
docker compose --profile observability up --build
docker compose --profile agents --profile observability up --build
```

The `agents` profile starts the Python LangGraph agent-worker shell and Ollama.
No Gemma model tag is hardcoded yet; the exact official tag must be verified
before making it a default. The `observability` profile starts OTel Collector,
Prometheus, Tempo, and Grafana.

Basic API smoke checks:

```bash
curl -fsS http://localhost:8080/api/health
curl -fsS -X POST http://localhost:8080/api/runs \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"support-ticket","input":"Create a ticket for login failure"}'
curl -fsS http://localhost:8080/api/runs
curl -fsS http://localhost:8080/api/approvals/pending
```

Run creation starts a Temporal workflow. The workflow writes projection updates,
creates a pending approval, waits for an approval signal, and then marks the run
`COMPLETED` or `REJECTED`.

## Development Checks

```bash
cd services/api && mvn test
cd services/agent-worker && uv run python -m unittest discover -s tests
cd services/web && npm run test:contract && npm run build
node scripts/check-compose-contract.mjs
docker compose config
```

## License

Apache-2.0
