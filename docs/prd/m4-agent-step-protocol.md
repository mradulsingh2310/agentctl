# PRD: M4 Agent Step Protocol And Python Worker Runtime

Status: Draft v0.1  
Owner: Mradul Singh  
Date: 2026-07-07  
Parent: `agentctl` platform PRD  
Repo: `github.com/mradulsingh2310/agentctl`

## 1. Product Summary

M4 turns the current Temporal approval scaffold into a real agent execution
boundary. The Java Temporal worker must call a Python worker over a versioned
HTTP step protocol. The Python worker hosts LangGraph/LangChain code and returns
structured step results that Java can persist, inspect, and route into approval
or terminal workflow states.

This milestone does not implement fake ticketing or GitHub Issues side effects.
It creates the durable handoff between Java/Temporal and Python/LangGraph so the
support-ticket agent can be implemented cleanly in M5.

## 2. Current Baseline

Already implemented:

- `POST /api/runs` creates a run projection and starts a Temporal workflow.
- Java workflow marks a run running, creates a hardcoded support-ticket approval,
  waits for an approval signal, then completes or rejects.
- Approval APIs update projections and signal Temporal.
- Python worker is only a long-running CLI process with `--health`.
- Dashboard is static fixture UI.

Critical gap:

- Temporal does not invoke Python.
- Python does not expose a step API.
- There are no step, tool-call, model-call, artifact, or trace-correlation
  projections.

## 3. Goals

- Define a versioned Java-to-Python agent step protocol.
- Add a Python HTTP runtime for the agent worker.
- Add one deterministic support-ticket draft step that can run without external
  credentials.
- Use LangGraph as the Python graph boundary and LangChain as the model boundary.
- Keep model calls stubbed or local-configurable so M4 is deterministic in CI.
- Extend the Java Temporal workflow to call Python through an activity.
- Persist step projections and audit events in Postgres.
- Keep human approval as a Temporal signal wait when the Python step asks for approval.
- Keep tenant ID present on every persisted row and every worker request.

## 4. Non-Goals

- No fake ticketing backend mutations in M4.
- No GitHub Issues calls in M4.
- No fga-gateway enforcement in M4 beyond carrying fields needed later.
- No LLM-as-judge eval runner in M4.
- No dashboard rewrite beyond exposing data needed by later dashboard work.
- No production auth or API-key enforcement in M4.
- No backward compatibility work before stable release.

## 5. Architecture Decision

Java Temporal worker calls the Python agent worker over HTTP.

Reasons:

- Temporal execution truth stays in Java/Spring as already chosen.
- Python remains focused on LangGraph, LangChain, agent code, and tool adapters.
- The HTTP boundary is easy to run with Docker Compose and easy to test with a
  fake server in Java tests.
- Future users can replace the Python worker implementation while preserving the
  protocol.

Python must not own Temporal workflow history in this milestone.

## 6. Step Protocol

Endpoint:

```text
POST /v1/agent-steps
```

Headers:

- `X-Agentctl-Tenant`
- `X-Agentctl-Run-Id`
- `X-Agentctl-Correlation-Id`

Request:

```json
{
  "protocolVersion": "2026-07-07",
  "tenantId": "tenant_a",
  "runId": "run_123",
  "agentId": "support-ticket",
  "stepId": "step_001",
  "stepType": "draft_ticket",
  "input": "Create a support ticket for checkout HTTP 500",
  "modelProfile": {
    "provider": "local",
    "model": "stub"
  },
  "toolContext": {
    "backend": "none"
  },
  "traceContext": {
    "correlationId": "corr_123"
  }
}
```

Response when approval is required:

```json
{
  "protocolVersion": "2026-07-07",
  "stepId": "step_001",
  "status": "WAITING_FOR_APPROVAL",
  "summary": "Drafted a bug ticket for checkout HTTP 500.",
  "output": {
    "ticket": {
      "title": "Checkout fails with HTTP 500",
      "body": "User reported checkout failing with HTTP 500.",
      "severity": "high",
      "labels": ["bug", "checkout"]
    }
  },
  "approvalRequest": {
    "toolName": "support_ticket.approve_draft",
    "question": "Approve this ticket draft before any ticket backend mutation?"
  },
  "toolCalls": [],
  "modelUsage": {
    "provider": "stub",
    "model": "stub",
    "inputTokens": 0,
    "outputTokens": 0
  }
}
```

Response when completed without approval:

```json
{
  "protocolVersion": "2026-07-07",
  "stepId": "step_001",
  "status": "COMPLETED",
  "summary": "No external action required.",
  "output": {},
  "toolCalls": [],
  "modelUsage": {
    "provider": "stub",
    "model": "stub",
    "inputTokens": 0,
    "outputTokens": 0
  }
}
```

Failure response:

```json
{
  "protocolVersion": "2026-07-07",
  "stepId": "step_001",
  "status": "FAILED",
  "summary": "Unsupported agent id.",
  "error": {
    "code": "UNSUPPORTED_AGENT",
    "message": "No agent is registered for agentId=unknown-agent",
    "retryable": false
  },
  "toolCalls": []
}
```

## 7. Java/Spring Requirements

Add configuration:

- `AGENTCTL_AGENT_WORKER_BASE_URL`, default `http://agentctl-agent-worker:8090`
- `AGENTCTL_AGENT_STEP_TIMEOUT`, default `30s`
- `AGENTCTL_AGENT_STEP_PROTOCOL_VERSION`, default `2026-07-07`

Add Java records/classes:

- `AgentStepRequest`
- `AgentStepResponse`
- `AgentStepApprovalRequest`
- `AgentStepModelUsage`
- `AgentStepToolCall`
- `AgentStepError`

Add activity:

- `AgentStepActivities.callAgentStep(AgentStepRequest request)`

Workflow changes:

- Start run.
- Call Python step activity.
- Persist step result.
- If Python returns `WAITING_FOR_APPROVAL`, create an approval projection from
  the response and wait for signal.
- If approved, mark M4 run `COMPLETED`; M5 will execute backend mutations after approval.
- If rejected, mark run `REJECTED`.
- If Python returns `COMPLETED`, mark run `COMPLETED`.
- If Python returns `FAILED`, mark run `FAILED`.

## 8. Python Worker Requirements

Runtime:

- Expose HTTP server on port `8090`.
- `GET /health` returns service status.
- `POST /v1/agent-steps` validates and handles the step request.

Agent registry:

- Support `agentId=support-ticket`.
- Unknown agents return `FAILED` with `UNSUPPORTED_AGENT`.

Support-ticket draft behavior:

- Parse the user input deterministically enough for tests.
- Produce title, body, severity, and labels.
- Return `WAITING_FOR_APPROVAL` for ticket-like requests.
- Do not call fake ticketing or GitHub.

LangGraph/LangChain:

- Include LangGraph and LangChain dependencies after implementation-time version verification.
- Use a deterministic stub model profile for CI.
- Keep Ollama/Gemma profile configurable but not required for tests.

## 9. Data Model Additions

Add `agent_steps`:

- `tenant_id`
- `id`
- `run_id`
- `step_type`
- `status`
- `summary`
- `input`
- `output_json`
- `error_code`
- `error_message`
- `created_at`
- `updated_at`

Add `model_calls`:

- `tenant_id`
- `id`
- `run_id`
- `step_id`
- `provider`
- `model`
- `input_tokens`
- `output_tokens`
- `cost_estimate`
- `created_at`

Add `tool_calls` as a projection table now, even if M4 stores no mutation calls:

- `tenant_id`
- `id`
- `run_id`
- `step_id`
- `tool_name`
- `operation_id`
- `status`
- `backend`
- `external_url`
- `fga_decision_id`
- `created_at`
- `updated_at`

## 10. Error Handling

- Java activity timeouts fail the run with `FAILED`.
- Python validation errors return HTTP 400 and Java records `FAILED`.
- Python 5xx errors are retryable by Temporal activity retry policy.
- Unsupported agent errors are non-retryable workflow failures mapped to `FAILED`.
- Tenant/run ID mismatch between headers and body returns HTTP 400.

## 11. Testing Requirements

Java:

- Workflow test where Python step returns `WAITING_FOR_APPROVAL`.
- Workflow test where Python step returns `COMPLETED`.
- Workflow test where Python step returns `FAILED`.
- Activity test using a local fake HTTP server.
- Control-plane API test proving run starts workflow with correct agent input.

Python:

- Unit tests for request validation.
- Unit tests for support-ticket draft output.
- HTTP tests for `/health` and `/v1/agent-steps`.
- Test unknown agent returns structured failure.

Contract:

- Add a shared JSON fixture for request/response examples.
- Java and Python tests both load the fixture.

## 12. Acceptance Criteria

- `docker compose --profile agents up --build` starts API, Java worker, Python worker, Temporal, Postgres, MinIO, and web.
- Creating a `support-ticket` run invokes the Python worker.
- Python returns a ticket draft and approval request.
- Java persists an `agent_steps` row.
- Approval API signals Temporal and the run completes or rejects.
- No fake ticket or GitHub mutation happens in M4.
- Tests pass for Java, Python, dashboard contract, and Compose contract.

## 13. Implementation Slices

1. Add protocol records and JSON fixtures.
2. Add Python HTTP runtime with health and step endpoint.
3. Add deterministic support-ticket draft handler.
4. Add Java HTTP client activity and tests.
5. Add step/model/tool projection schema.
6. Wire `RunWorkflowImpl` to call the Python step activity.
7. Update Compose to expose Python worker HTTP port inside the `agents` profile.
8. Update README smoke commands for M4.
