# PRD: agentctl Platform

Status: Draft v0.1  
Owner: Mradul Singh  
Date: 2026-07-07  
Repo: `github.com/mradulsingh2310/agentctl`

## 1. Product Summary

`agentctl` is a self-hosted durable control plane for LangGraph agents. It lets a
developer clone the repo, run Docker Compose, start a production-shaped local
stack, and operate long-running agent workflows with Temporal-backed execution,
human approvals, model/cost telemetry, eval gates, and an operational dashboard.

The platform does not implement an authoritative write-ahead log in v1. Temporal
is the source of truth for execution recovery, retries, timers, and signals.
Tools own their own idempotency and reconciliation. `agentctl` stores product
projections for dashboard queries, audit views, cost attribution, eval results,
and trace correlation.

## 2. Target Users

- Agent platform engineers who need a self-hosted runtime for long-running agents.
- Backend engineers embedding agents into existing product workflows.
- SREs who need visibility into stuck, failed, suspended, or expensive runs.
- Evaluation owners who need CI gates for agent behavior regressions.
- Human approvers who need enough context to safely approve or reject agent side effects.

## 3. Goals

- Provide a clone-and-run local platform with `docker compose up`.
- Use Java/Spring as the control plane and Temporal Java workflows as execution truth.
- Use Python LangGraph agents through a versioned step protocol.
- Use LangChain for model abstraction and local Ollama/Gemma 4 as the default self-hosted path.
- Provide a Next.js operational dashboard for runs, approvals, tools, cost, evals, and trace links.
- Store control-plane data in Postgres and large artifacts in MinIO/S3-compatible storage.
- Emit OpenTelemetry traces and metrics into a local OTel/Grafana stack.
- Support durable human approval through Temporal signals from dashboard and CLI.
- Support LLM-as-judge eval gates in v1, with explicit thresholds and CI artifacts.
- Integrate with `fga-gateway` for production authorization of agent tool calls.

## 4. Non-Goals

- No hosted SaaS control plane in v1.
- No authoritative custom WAL or Temporal replacement in v1.
- No backward compatibility guarantees before a stable release unless explicitly requested.
- No enterprise SSO/OIDC in v1.
- No broad marketplace of agent templates in v1.
- No guarantee of exactly-once side effects for tools that do not implement idempotency or reconciliation.

## 5. Architecture

### 5.1 Local Docker Stack

`docker compose up` must start:

- `agentctl-api`: Java/Spring control plane.
- `agentctl-worker`: Java Temporal worker hosting workflows and platform activities.
- `agentctl-agent-worker`: Python worker hosting LangGraph agents and tool adapters.
- `agentctl-web`: Next.js operational dashboard.
- `temporal`: Temporal server for local durable execution.
- `postgres`: control-plane database and Temporal persistence where appropriate.
- `minio`: S3-compatible artifact storage.
- `ollama`: local model service, configured for Gemma 4 after exact tag verification.
- `otel-collector`: telemetry ingestion.
- `prometheus`: metrics storage.
- `tempo` or `jaeger`: trace storage.
- `grafana`: dashboards.

### 5.2 Execution Truth

Temporal owns:

- workflow history
- retries and retry policy
- timers
- approval waits
- workflow signals
- activity scheduling
- worker ownership
- crash recovery

`agentctl` owns:

- run metadata and searchable projections
- approval request records and dashboard state
- tool-call summaries
- model-call summaries
- token and cost records
- eval run artifacts
- trace correlation metadata

The projection database must be allowed to lag behind Temporal without corrupting
execution. If projection state disagrees with Temporal workflow state, Temporal
state wins and a projection repair job must be able to rebuild derived rows.

### 5.3 Component Boundaries

Java/Spring control plane:

- REST APIs for dashboard, CLI, runs, approvals, evals, providers, and config.
- Temporal workflow interfaces and implementations.
- Projection writers for run lifecycle events.
- Auth, tenant boundaries, service tokens, and fga-gateway integration.

Python CLI/SDK:

- `agentctl init`
- `agentctl run`
- `agentctl approvals approve|reject`
- `agentctl eval run`
- LangGraph integration helpers.
- Tool decorators and fga-gateway manifest export.

Python agent worker:

- Hosts LangGraph agent implementations.
- Calls LangChain chat models.
- Calls local and external tools.
- Performs fga-gateway preflight before protected tool calls.
- Reports step summaries back to the Java control plane.

Next.js dashboard:

- Consumes Java/Spring APIs.
- Does not own durable state.
- Uses route handlers only as a dashboard backend-for-frontend when needed.

## 6. Runtime Model

### 6.1 Run Lifecycle

Run statuses:

- `CREATED`
- `RUNNING`
- `WAITING_FOR_APPROVAL`
- `APPROVED`
- `REJECTED`
- `FAILED`
- `COMPLETED`
- `CANCELLED`

Temporal workflow states may be richer internally, but dashboard state must map
to this public lifecycle.

### 6.2 Java Temporal Workflow

The core workflow is `AgentRunWorkflow`.

Inputs:

- `tenant_id`
- `agent_id`
- `run_id`
- `input_ref`
- `model_profile`
- `tool_policy_profile`
- `eval_context`, optional

Workflow responsibilities:

- create durable run
- invoke Python agent step activities
- wait for approval signals
- apply retry and timeout policies
- emit projection events
- mark terminal status

Signals:

- `approveApproval(approval_id, approver_id, reason)`
- `rejectApproval(approval_id, approver_id, reason)`
- `cancelRun(actor_id, reason)`

Activities:

- `StartAgentStepActivity`
- `CallAgentModelActivity`
- `ExecuteToolActivity`
- `RequestApprovalActivity`
- `WriteProjectionActivity`
- `WriteArtifactActivity`
- `RunEvalCaseActivity`

### 6.3 Python LangGraph Step Protocol

The Python SDK must not run as an opaque black box. It participates in a step
protocol so Java/Temporal can schedule durable boundaries.

Minimum step request fields:

- `tenant_id`
- `run_id`
- `agent_id`
- `step_id`
- `step_type`
- `input_ref`
- `model_profile`
- `tool_context`
- `trace_context`

Minimum step response fields:

- `step_id`
- `status`
- `output_ref`
- `tool_calls`
- `approval_request`, optional
- `model_usage`, optional
- `error`, optional

## 7. Model Provider Requirements

- Product boundary is LangChain/LangGraph, not Anthropic/OpenAI-specific APIs.
- Default local path is Ollama with Gemma 4. Implementation must verify the exact official Ollama tag before hardcoding it.
- The setup flow must fail clearly if the configured Ollama model is missing.
- Provider profiles must allow future cloud providers without changing the Java workflow contract.
- Model usage must record input tokens, output tokens, model name, provider profile, and cost estimate when available.

## 8. Storage

Postgres stores:

- tenants
- users
- API keys/service tokens
- runs
- workflow projection rows
- approvals
- tools
- tool calls
- model calls
- cost summaries
- eval runs
- eval cases
- audit records

MinIO/S3 stores:

- run inputs and outputs
- large prompts and responses when capture is enabled
- tool payloads
- eval artifacts
- screenshots or trace exports, if generated

Sensitive payload capture must be off or redacted by default where practical.

## 9. Authentication And Authorization

V1 identity:

- local users
- password login for dashboard
- API keys/service tokens for CLI, SDK, and workers

Tenant boundary:

- every user belongs to at least one tenant
- every run, tool, approval, model call, eval, artifact, and audit row has `tenant_id`
- all APIs must scope by tenant
- cross-tenant reads and writes are blocked

Authorization:

- local auth adapter is acceptable for development
- production tool authorization integrates with `fga-gateway`
- `agentctl` must fail closed when a protected tool requires fga-gateway and fga-gateway is unavailable

## 10. Human Approval

Approvals are first-class durable workflow waits.

Requirements:

- approval requests are persisted in Postgres projection tables
- workflow waits on Temporal signal
- dashboard can approve and reject
- CLI can approve and reject
- approval context is stored as an artifact ref when large
- rejection behavior is deterministic and visible
- approval events emit OTel spans and audit records

## 11. Operational Dashboard

Next.js dashboard pages:

- Runs list
- Run detail with timeline
- Pending approvals
- Approval detail
- Tool-call detail
- Model/cost usage
- Eval runs
- Eval result detail
- Tenant settings
- Provider profiles
- Service tokens
- Trace links and Grafana links

Dashboard must be functional, dense, and operational. It is not a marketing page.

## 12. Observability

Required spans:

- `agentctl.run`
- `agentctl.workflow`
- `agentctl.activity`
- `agentctl.model.call`
- `agentctl.tool.call`
- `agentctl.approval.request`
- `agentctl.approval.signal`
- `agentctl.eval.case`
- `agentctl.fga.preflight`

Required metrics:

- runs started/completed/failed/suspended
- approval wait duration
- model calls and token usage
- tool calls by outcome
- eval cases by outcome
- fga-gateway allow/deny counts
- Temporal workflow retry counts

## 13. Eval Gates

V1 includes LLM-as-judge gates.

Requirements:

- `agentctl eval run` executes configured suites
- suites can use deterministic assertions and LLM-as-judge assertions
- judge model uses LangChain provider config
- outputs include JSON artifact and human-readable summary
- CI exits non-zero on regression
- failures include case ID, expected behavior, observed output, judge rationale, cost, latency, and trace link when available

Minimum eval dimensions:

- approval required before side effect
- no unauthorized tool call
- ticket schema validity
- issue workflow correctness
- cost ceiling
- latency ceiling

## 14. fga-gateway Integration

`agentctl` must support:

- fga-gateway base URL configuration
- service token authentication to fga-gateway
- preflight authorization before protected in-process tools
- proxy mode for HTTP/MCP tool calls
- audit correlation IDs passed from run to fga-gateway
- fail-closed behavior for protected tools

## 15. Milestones

M0: Repo and PRDs

- Create repo shell, PRDs, license, README.

M1: Compose Skeleton

- Add Docker Compose with Temporal, Postgres, MinIO, OTel/Grafana, Java API service shell, Next.js service shell, Python worker service shell.

M2: Java Control Plane

- Spring APIs, local auth, tenant model, Temporal client, projection schema.

M3: Temporal Run Workflow

- Java workflow, approval signal, projection events, retry policies.

M4: Python LangGraph Worker

- Python SDK/CLI, LangGraph example, LangChain/Ollama profile.

M5: Support Ticket Agent

- Fake ticketing and GitHub Issues workflow.

M6: Dashboard

- Runs, approvals, tool calls, costs, evals, trace links.

M7: Eval Gates

- LLM-as-judge, CI artifacts, non-zero regression exit.

M8: fga-gateway Integration

- Preflight and proxy enforcement for protected tools.

## 16. Acceptance Criteria

- `docker compose up` starts the local platform and dashboard.
- A support-ticket run works with fake ticketing and no external credentials.
- The same flow works with GitHub Issues when token/repo config is present.
- Human approval works from dashboard and CLI.
- Temporal remains the only execution recovery source of truth.
- Projection repair can reconcile dashboard state from workflow state.
- Eval gate can fail CI with clear artifacts.
- OTel/Grafana shows run, model, tool, approval, authz, and eval telemetry.
