# PRD: M1 Compose Hardening And M2 Control-Plane Foundation

Status: Superseded v0.1
Owner: Mradul Singh  
Date: 2026-07-07  
Repo: `github.com/mradulsingh2310/agentctl`

## Supersession Note

This PRD is retained as historical context for the M1/M2 foundation work. It is
superseded by `docs/prd/agentctl-platform.md` for current Compose behavior and
by later milestone PRDs for implementation planning.

The main superseded assumption is that `docker compose up` starts every optional
service by default. Current product direction keeps the default local path light:
API, Java worker, web, Temporal, Postgres, and MinIO start by default; Python
agent worker/Ollama use the `agents` profile; OTel/Prometheus/Tempo/Grafana use
the `observability` profile.

Do not use this document as current acceptance criteria for default Compose
profiles. Use it only to understand why the initial schema, run APIs, approval
APIs, audit APIs, and healthchecked service shells exist.

## 1. Scope

This PRD covers the next implementation slice after the initial skeleton:

- M1 hardening: make `docker compose up` a real local smoke path.
- M2 foundation: add the first Postgres-backed control-plane projections and REST APIs.

This slice intentionally does not implement the Temporal workflow engine. Temporal
remains the execution source of truth for later workflow execution, but these
APIs are product projections used by the dashboard and CLI.

## 2. Goals

- `docker compose up` starts the first-party API, web dashboard, Java worker shell,
  Python agent-worker shell, Postgres, Temporal, MinIO, Ollama, OTel Collector,
  Prometheus, Tempo, and Grafana.
- Compose config includes healthchecks for first-party services and core backing services.
- API service connects to Postgres in local compose.
- Flyway migrations create the initial control-plane projection schema.
- REST APIs support creating, listing, and reading runs.
- REST APIs support listing, approving, and rejecting approval projections.
- REST APIs expose a run-scoped audit timeline.
- Every persisted product row has `tenant_id`.

## 3. Non-Goals

- No Temporal workflow start/resume behavior in this slice.
- No production auth, password login, or API-key enforcement in this slice.
- No real LangGraph execution in this slice.
- No GitHub Issues integration in this slice.
- No fga-gateway callout in this slice.
- No backward compatibility work before stable release.

## 4. Local Tenant Behavior

For local development, requests may omit an explicit tenant header. The API then
uses `local-dev` as the tenant id. API clients may pass `X-Agentctl-Tenant` to
select a tenant. This is a development convenience only; the schema and APIs must
still treat tenant as a hard boundary and every query must scope by tenant.

## 5. Compose Hardening Requirements

Required first-party service behavior:

- `agentctl-api` stays running and exposes `/api/health`.
- `agentctl-worker` stays running as a Java worker shell.
- `agentctl-agent-worker` stays running as a Python worker shell and supports a
  command-level health probe.
- `agentctl-web` starts and serves the dashboard on port `3000`.

Required backing service behavior:

- Postgres exposes readiness through `pg_isready`.
- MinIO exposes liveness through its health endpoint.
- Temporal exposes a basic local readiness probe.
- OTel Collector exposes a health-check extension endpoint.
- Prometheus, Tempo, and Grafana are present in compose and reachable through
  their mapped ports.

The smoke path for this slice is:

```bash
docker compose up --build
```

The stack does not need to run real agent workflows yet, but first-party services
must not immediately exit.

## 6. Database Schema

Initial tables:

- `tenants`
- `users`
- `runs`
- `approvals`
- `audit_events`

### 6.1 tenants

Fields:

- `id`
- `display_name`
- `created_at`

### 6.2 users

Fields:

- `tenant_id`
- `id`
- `display_name`
- `created_at`

### 6.3 runs

Fields:

- `tenant_id`
- `id`
- `agent_id`
- `status`
- `input`
- `created_at`
- `updated_at`

Allowed statuses:

- `CREATED`
- `RUNNING`
- `WAITING_FOR_APPROVAL`
- `APPROVED`
- `REJECTED`
- `FAILED`
- `COMPLETED`
- `CANCELLED`

### 6.4 approvals

Fields:

- `tenant_id`
- `id`
- `run_id`
- `status`
- `tool_name`
- `question`
- `created_at`
- `decided_at`
- `decided_by`
- `decision_reason`

Allowed statuses:

- `PENDING`
- `APPROVED`
- `REJECTED`

### 6.5 audit_events

Fields:

- `tenant_id`
- `id`
- `run_id`
- `event_type`
- `message`
- `created_at`

## 7. REST API Requirements

All APIs are under `/api`.

### 7.1 Create Run

`POST /api/runs`

Request:

```json
{
  "agentId": "support-ticket",
  "input": "Create a support ticket for login failure"
}
```

Response:

```json
{
  "id": "run_...",
  "agentId": "support-ticket",
  "status": "CREATED",
  "input": "Create a support ticket for login failure",
  "createdAt": "...",
  "updatedAt": "..."
}
```

Side effects:

- Creates a `runs` row.
- Writes an audit event `RUN_CREATED`.

### 7.2 List Runs

`GET /api/runs`

Response:

```json
{
  "items": []
}
```

Rules:

- Returns runs for the request tenant only.
- Ordered newest first.

### 7.3 Get Run

`GET /api/runs/{runId}`

Rules:

- Returns `404` if the run is absent or belongs to another tenant.

### 7.4 List Pending Approvals

`GET /api/approvals/pending`

Rules:

- Returns pending approvals for the request tenant only.
- Ordered oldest first.

### 7.5 Approve Approval

`POST /api/approvals/{approvalId}/approve`

Request:

```json
{
  "actorId": "user_local",
  "reason": "Looks safe"
}
```

Rules:

- Changes status from `PENDING` to `APPROVED`.
- Records `decided_at`, `decided_by`, and `decision_reason`.
- Writes audit event `APPROVAL_APPROVED`.
- Returns `409` if approval is not pending.

### 7.6 Reject Approval

`POST /api/approvals/{approvalId}/reject`

Request:

```json
{
  "actorId": "user_local",
  "reason": "Needs more context"
}
```

Rules:

- Changes status from `PENDING` to `REJECTED`.
- Records `decided_at`, `decided_by`, and `decision_reason`.
- Writes audit event `APPROVAL_REJECTED`.
- Returns `409` if approval is not pending.

### 7.7 Run Audit Timeline

`GET /api/runs/{runId}/audit`

Rules:

- Returns audit events for the run and request tenant only.
- Ordered oldest first.

## 8. Testing Requirements

- API tests must verify tenant scoping.
- API tests must verify run creation writes an audit event.
- API tests must verify approval approve/reject transitions.
- API tests must verify non-pending approvals cannot be decided twice.
- Migration tests must verify the app starts and Flyway applies the schema.
- Compose contract test must verify required service names and healthchecks exist.

## 9. Acceptance Criteria

- `docker compose config` succeeds.
- First-party Docker images build.
- API tests pass.
- Web build still passes.
- Python worker tests pass.
- Compose contract test passes and checks the healthcheck surface.
- README documents the smoke path and M2 API checks.
