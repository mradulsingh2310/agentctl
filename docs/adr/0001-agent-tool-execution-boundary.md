# ADR-0001: Agent Tool Execution Boundary

Status: Accepted
Date: 2026-07-08
Owner: Mradul Singh
Related PRD: `docs/prd/m5-agent-tool-execution-boundary.md`

## Context

M4 established a Java/Spring + Temporal control plane that calls a Python
FastAPI agent worker over a versioned step protocol. The Python worker hosts the
support-ticket LangGraph draft step. Java owns run lifecycle, approval signals,
and product projections.

M5 adds post-approval tool execution for fake ticketing and GitHub Issues. The
main architecture decision is where tool execution lives:

- Java already owns Temporal durability and dashboard projections.
- Python already owns LangGraph agent behavior and LangChain model boundaries.
- Tool idempotency must live with the tool adapter that understands backend
  semantics.
- The system must avoid introducing an authoritative `agentctl` WAL.

## Decision

Agent tool execution lives in the Python agent worker.

Java/Spring remains responsible for:

- Temporal workflow orchestration.
- Approval request creation and approval signal handling.
- Flyway migrations for all shared tables.
- Run, step, ticket, tool-call, audit, model-call, and eval projections.
- Dashboard/CLI read APIs.
- Calling Python only at explicit workflow points.

Python remains responsible for:

- LangGraph support-ticket graph behavior.
- LangChain tool definitions.
- Fake-ticket and GitHub Issues tool adapters.
- Tool-owned idempotency checks.
- Backend mutation execution.
- Returning structured `AgentStepResponse` artifacts to Java.

Python may write to tool-owned operational tables such as `fake_tickets`,
`fake_ticket_events`, and `tool_idempotency_records`. Java writes
dashboard-facing projection tables from the Python response. All schema changes
are still created by Java/Flyway migrations.

## Rationale

- Tool behavior is agent behavior. Keeping it in Python keeps the LangGraph plan,
  LangChain tool declarations, and backend adapter logic together.
- Java should not become a second tool runtime. Its durable value is Temporal,
  approval, schema, and projection management.
- Tool-owned idempotency is easier to make correct when implemented next to the
  backend-specific mutation code.
- Fake ticketing and GitHub Issues can share one Python tool interface while
  Java sees the same step response shape for both.
- The boundary keeps M4's execution model intact: Temporal is truth, tools own
  side-effect idempotency, and `agentctl` stores product projections.

## Alternatives Considered

### Alternative 1: Java Executes All Tools

Java would implement fake ticketing, GitHub clients, idempotency, and mutation
logic. Python would only draft and ask for approval.

Rejected because:

- It splits one agent workflow across two runtimes.
- LangChain tool definitions become wrappers around Java services instead of
  real tool implementations.
- Java becomes a second agent/tool platform.
- GitHub idempotency and backend reconciliation drift away from the tool adapter.

### Alternative 2: Python Owns Everything Including Temporal

Python would become the workflow worker and Java would only serve the dashboard.

Rejected because:

- M4 already established Java Temporal workflows.
- Existing approval signal, projection, and API code is in Java.
- It would force a large rewrite before the support-ticket v1 proves product
  value.

### Alternative 3: Java Exposes Tool APIs, Python Calls Java

Python would run LangGraph but call Java HTTP endpoints for fake/GitHub tools.

Rejected for M5 because:

- It hides tool idempotency in the control plane.
- It creates an internal API surface before there are multiple tool runtimes.
- It makes retries cross two application protocols instead of one Temporal
  activity plus backend-owned idempotency.

## Consequences

Positive:

- Python tool adapters can be tested directly.
- Java workflow logic stays small and deterministic.
- The same execute-step protocol can support fake and GitHub backends.
- Tool idempotency remains backend-aware.
- Dashboard projections stay query-optimized and non-authoritative.

Negative:

- Python needs database access for fake-ticket and idempotency operational state.
- Java and Python share one schema, so migration ownership must stay explicit.
- Projection writes happen after Python returns, so Java must make projection
  activities idempotent.
- Local Docker Compose must configure both Java and Python with the same
  Postgres tenant boundary assumptions.

## Invariants

- Python must not mutate any backend before Java sends an approved
  `execute_ticket_workflow` step.
- Java must not execute fake-ticket or GitHub mutations directly.
- Every mutation must carry a stable `operationId`.
- Every mutation must check idempotency before touching a backend.
- Rejected approvals must produce no backend mutation.
- Dashboard projection rows are not execution truth.
- Tenant/org remains a hard boundary in every tool-owned and projection table.

## Follow-Up Work

- Add V3 migration for ticket and idempotency tables.
- Add Python fake-ticket operational repositories.
- Add Python execute-step handler.
- Wire Java workflow to call execute step after approval.
- Add ticket projection read APIs.
- Add GitHub Issues adapter using the same tool contract.
- Add evals for approval-before-mutation and idempotency.
