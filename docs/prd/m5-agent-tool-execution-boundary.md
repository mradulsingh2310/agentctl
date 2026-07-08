# PRD: M5 Agent Tool Execution Boundary

Status: Draft v0.1
Owner: Mradul Singh
Date: 2026-07-08
Parent: `docs/prd/m5-support-ticket-v1.md`
Related ADR: `docs/adr/0001-agent-tool-execution-boundary.md`
Repo: `github.com/mradulsingh2310/agentctl`

## 1. Summary

M5 needs to turn the support-ticket draft flow into a real post-approval tool
execution flow. This PRD scopes the service boundary for that work before
implementation: Java/Spring + Temporal remains the durable control plane, while
the Python agent worker owns LangGraph planning and LangChain tool execution.

The first implementation target is fake ticketing. GitHub Issues uses the same
tool contract later in M5 after the fake backend proves approval, idempotency,
projection, and retry behavior locally.

## 2. Explicit Assumptions

- The phrase "great" in the planning discussion means we should document the
  Python-owned tool-execution boundary before implementation.
- Java owns Temporal workflow state, approval signals, Flyway migrations, and
  product projections.
- Python owns LangGraph nodes, LangChain tool adapters, fake/GitHub tool
  execution, and tool-owned idempotency behavior.
- Python may write to tool-owned operational tables such as `fake_tickets`,
  `fake_ticket_events`, and `tool_idempotency_records`.
- Java persists dashboard-facing projection rows such as `tickets`,
  `tool_calls`, `agent_steps`, audit events, and run status.
- No backward compatibility is required before a stable public release.

## 3. Problem

M4 created a durable Java-to-Python step protocol, but the workflow still stops
after approval. M5 needs a clean way to execute backend mutations after approval
without splitting agent logic across Java and Python.

Bad outcomes to avoid:

- Java becoming a second agent/tool runtime.
- Python executing tools before Temporal records approval.
- Tool idempotency living only in the control plane instead of in the tool
  adapter that understands the backend.
- Fake ticketing and GitHub Issues using different behavioral contracts.
- Dashboard projections becoming the source of execution truth.

## 4. Goals

- Execute fake-ticket tools after approval with no external credentials.
- Preserve M4's rule that Temporal is execution truth.
- Keep the agent graph, tool planning, and tool execution in Python.
- Keep Java responsible for workflow recovery, approval state, schema
  migrations, and product projections.
- Make every mutation retryable through tool-owned idempotency.
- Return enough artifacts from Python for Java to project runs, tickets, tool
  calls, model calls, eval artifacts, and audit events.
- Reuse the same execution contract for fake ticketing and GitHub Issues.

## 5. Non-Goals

- No Jira, Slack, email, PagerDuty, or PR-creation work.
- No fga-gateway enforcement beyond a placeholder authorizer interface.
- No generalized plugin system.
- No direct dashboard write path to tool backends.
- No production SSO or multi-region deployment work.
- No durable `agentctl` WAL.

## 6. Service Boundary

Java/Spring control plane responsibilities:

- Start runs.
- Call the Python draft step.
- Persist draft step projections.
- Create approval requests.
- Wait for Temporal approval signals.
- Call the Python execute step after approval.
- Persist execute step projections.
- Mark the run `COMPLETED`, `REJECTED`, or `FAILED`.
- Apply Flyway migrations for all shared database tables.
- Serve dashboard/CLI read APIs.

Python agent worker responsibilities:

- Host the support-ticket LangGraph.
- Validate ticket draft schema.
- Convert an approval signal into an execution plan.
- Execute fake-ticket tools.
- Execute GitHub Issues tools when configured.
- Own operation IDs and idempotency behavior.
- Return structured step responses to Java.
- Never execute a backend mutation before Java sends the approved execute step.

Shared Postgres responsibilities:

- Java/Flyway creates schema.
- Python writes tool-owned operational rows.
- Java writes dashboard-facing projection rows from Python step responses.
- Both services filter every read/write by `tenant_id`.

## 7. Step Protocol Additions

M5 adds one new step type:

- `execute_ticket_workflow`

The Java workflow calls this step only after an approval signal is accepted.

Example request:

```json
{
  "protocolVersion": "2026-07-07",
  "tenantId": "tenant_a",
  "runId": "run_123",
  "agentId": "support-ticket",
  "stepId": "step_execute_run_123",
  "stepType": "execute_ticket_workflow",
  "input": "Create a support ticket for checkout failing with HTTP 500",
  "modelProfile": {
    "provider": "stub",
    "model": "stub"
  },
  "toolContext": {
    "backend": "fake",
    "approval": {
      "approvalId": "approval_run_123",
      "actorId": "user_local",
      "reason": "Looks safe"
    },
    "draft": {
      "title": "Checkout fails with HTTP 500",
      "body": "User reported checkout failing with HTTP 500.",
      "severity": "high",
      "labels": ["bug", "checkout"],
      "assignee": null
    },
    "operationBaseId": "run_123:approval_run_123"
  },
  "traceContext": {}
}
```

Example successful response:

```json
{
  "protocolVersion": "2026-07-07",
  "stepId": "step_execute_run_123",
  "status": "COMPLETED",
  "summary": "Created fake ticket fake_run_123.",
  "output": {
    "ticket": {
      "backend": "fake",
      "externalTicketId": "fake_run_123",
      "externalUrl": null,
      "title": "Checkout fails with HTTP 500",
      "body": "User reported checkout failing with HTTP 500.",
      "status": "OPEN",
      "severity": "high",
      "labels": ["bug", "checkout"],
      "assignee": null,
      "idempotencyMarker": "agentctl:run_123:approval_run_123:fake_ticket.create"
    }
  },
  "approvalRequest": null,
  "toolCalls": [
    {
      "toolName": "fake_ticket.create",
      "operationId": "run_123:approval_run_123:fake_ticket.create",
      "status": "COMPLETED",
      "backend": "fake",
      "externalUrl": null,
      "fgaDecisionId": "local-dev",
      "metadata": {
        "externalTicketId": "fake_run_123"
      }
    }
  ],
  "modelUsage": {
    "provider": "stub",
    "model": "stub",
    "inputTokens": 0,
    "outputTokens": 0
  },
  "error": null
}
```

## 8. Idempotency Rules

- Java generates a stable `operationBaseId` from run and approval ids.
- Python tool adapters derive tool-specific `operationId` values from that base.
- Every mutation checks `tool_idempotency_records` before touching the backend.
- A completed record returns the previous response.
- An in-progress record returns a structured conflict and does not mutate.
- A failed record returns a structured failure unless the tool can prove backend
  state is already equivalent to success.
- GitHub create also writes an idempotency marker into the issue body and
  searches for that marker before creating a new issue.

## 9. Data Model Scope

M5 requires these new tables:

- `tickets`: Java-owned dashboard projection.
- `fake_tickets`: Python fake-backend operational state.
- `fake_ticket_events`: Python fake-backend event history.
- `tool_idempotency_records`: Python tool-owned idempotency state.

Existing M4 tables remain:

- `agent_steps`
- `model_calls`
- `tool_calls`
- `runs`
- `approvals`
- `audit_events`

## 10. Failure Behavior

- Rejected approval means Java never calls `execute_ticket_workflow`.
- Python validation failure returns `FAILED` and Java marks the run `FAILED`.
- Fake backend idempotency conflict returns `FAILED` and no duplicate ticket.
- GitHub missing config returns `FAILED` before any GitHub call.
- GitHub 401/403 returns non-retryable `FAILED`.
- GitHub rate limit returns retryable `FAILED` only when response headers make a
  safe retry window clear.
- Java activity retry never substitutes for tool idempotency.

## 11. Testing Requirements

Java:

- Workflow test for approved fake-ticket execution.
- Workflow test proving rejection does not call execute step.
- Projection test for `tickets`, `tool_calls`, and `agent_steps`.
- Retry test proving duplicate execute-step activity responses do not duplicate
  ticket projections.

Python:

- Unit test for `execute_ticket_workflow` fake create.
- Unit test for fake create idempotency replay.
- Unit test for in-progress idempotency conflict.
- Unit test proving unsupported backend returns structured `FAILED`.
- Graph test proving execute step is unreachable without approval context.

Contract:

- Shared JSON fixture for execute-step request.
- Shared JSON fixture for execute-step response.
- Java and Python tests load the same fixtures.

## 12. Acceptance Criteria

- A default local run uses fake ticketing.
- A rejected approval causes zero fake/GitHub backend mutation.
- An approved fake-ticket run creates one fake ticket.
- Replaying the same execute step returns the same fake ticket.
- Java projects the completed ticket into dashboard-readable tables.
- The run completes with the fake ticket id in the completion summary.
- GitHub code is not required for the first fake-ticket implementation slice.
- No tool mutation path exists from the dashboard or Java controllers.

## 13. Implementation Slices

1. Add V3 migration for ticket/idempotency tables.
2. Add shared execute-step JSON fixtures.
3. Add Python fake-ticket repository and idempotency repository.
4. Add Python fake-ticket LangChain tools.
5. Add Python `execute_ticket_workflow` handler.
6. Add Java workflow branch that calls execute step after approval.
7. Add Java projection support for ticket output.
8. Add dashboard/API read endpoints for ticket projections.
9. Add GitHub Issues adapter behind the same Python tool contract.
10. Add deterministic evals for approval-before-mutation and idempotency.
