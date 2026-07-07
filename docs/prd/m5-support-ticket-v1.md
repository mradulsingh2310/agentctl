# PRD: M5 Support Ticket V1 Implementation Slice

Status: Draft v0.1  
Owner: Mradul Singh  
Date: 2026-07-07  
Parent: `docs/prd/agents/support-ticket-agent.md`  
Repo: `github.com/mradulsingh2310/agentctl`

## 1. Product Summary

M5 makes the support-ticket agent real. It builds on the M4 Java-to-Python step
protocol and adds ticket backend execution after human approval. The first path
must work without external credentials through a deterministic fake ticketing
backend. The second path must work with real GitHub Issues when credentials and
repo configuration are provided.

## 2. Current Baseline

Expected after M4:

- Java Temporal workflow can call Python worker.
- Python worker can draft a support ticket and request approval.
- Java can persist step, model, and tool projections.
- Approval signals resume the workflow.

Still missing before M5:

- Fake ticketing backend.
- GitHub Issues adapter.
- Tool-owned idempotency.
- Backend mutation after approval.
- Ticket lifecycle projections.
- Eval cases proving approval-before-side-effect.

## 3. Goals

- Complete a fake-ticket support-ticket run with no external credentials.
- Complete a GitHub Issues support-ticket run when `GITHUB_TOKEN`,
  `GITHUB_OWNER`, and `GITHUB_REPO` are configured.
- Require approval before every externally visible GitHub mutation.
- Keep idempotency inside each ticket backend tool.
- Persist ticket lifecycle and tool-call projections.
- Produce enough artifacts for dashboard and eval gates.

## 4. Non-Goals

- No Jira, Slack, email, or PagerDuty integration.
- No autonomous PR creation.
- No generalized helpdesk product.
- No production SSO.
- No broad fga-gateway integration beyond a clear placeholder interface unless
  fga-gateway M2/M5 is ready.
- No backward compatibility work before stable release.

## 5. Backend Selection

Configuration:

- `AGENTCTL_TICKET_BACKEND=fake`, default.
- `AGENTCTL_TICKET_BACKEND=github`, requires GitHub config.

GitHub configuration:

- `GITHUB_TOKEN`
- `GITHUB_OWNER`
- `GITHUB_REPO`

Behavior:

- Fake backend is default for local demo and CI.
- GitHub backend fails clearly at startup or run validation if required config is missing.
- A run records which backend was selected.

## 6. Fake Ticketing Backend

Tool names:

- `fake_ticket.create`
- `fake_ticket.update`
- `fake_ticket.label`
- `fake_ticket.assign`
- `fake_ticket.comment`
- `fake_ticket.close`

Storage:

- `fake_tickets`
- `fake_ticket_events`
- `tool_idempotency_records`

Capabilities:

- Create ticket from approved draft.
- Update title/body.
- Add labels.
- Assign owner.
- Add comments.
- Close ticket.
- Search by idempotency key.

Idempotency:

- Every mutation receives `operationId`.
- Tool checks `tool_idempotency_records` before mutation.
- If an operation already succeeded, return the previous result.
- If an operation is in progress or failed, return a structured conflict/failure.

## 7. GitHub Issues Backend

Tool names:

- `github_issue.create`
- `github_issue.update`
- `github_issue.label`
- `github_issue.assign`
- `github_issue.comment`
- `github_issue.close`

Capabilities:

- Create issue.
- Edit issue title/body.
- Add labels.
- Assign users.
- Add comments.
- Close issue.
- Search for an existing issue by idempotency marker.

Idempotency:

- GitHub issue create writes an idempotency marker into the issue body.
- Before creating, the tool searches open and recently closed issues for that marker.
- Retrying with the same operation ID returns the existing issue.
- Update/comment/label/assign/close operations store local idempotency records and reconcile against GitHub state where possible.

## 8. Approval Policy

Approval required:

- Creating a GitHub issue.
- Closing a GitHub issue.
- Assigning a GitHub issue.
- Applying priority/severity labels to a GitHub issue.

Approval optional:

- Fake ticketing smoke path may skip approval only when demo/eval config explicitly says `skipApprovalForFakeBackend=true`.

Default:

- Ask for approval even on fake backend so the local demo exercises the full Temporal signal path.

## 9. Python Agent Behavior

LangGraph nodes:

- `parse_request`
- `classify_ticket_type`
- `draft_ticket`
- `validate_ticket_schema`
- `request_approval`
- `execute_backend_workflow`
- `summarize_result`

Ticket schema:

```json
{
  "title": "Checkout fails with HTTP 500",
  "body": "User reported checkout failing with HTTP 500.",
  "severity": "high",
  "labels": ["bug", "checkout"],
  "assignee": null
}
```

Rules:

- Do not mutate GitHub before approval.
- Do not close a ticket unless user intent explicitly asks to close.
- Do not invent assignees unless configured in owner hints.
- Preserve user-provided facts in the ticket body.

## 10. Java Workflow Behavior

Flow:

1. Start run.
2. Call Python draft step.
3. Persist draft and approval request.
4. Wait for approval signal.
5. On rejection, mark run `REJECTED` and do not call backend tools.
6. On approval, call Python execute step.
7. Python executes selected backend tools.
8. Java persists ticket/tool projections from step response.
9. Mark run `COMPLETED` with ticket URL or fake ticket ID.

Failure behavior:

- Backend config missing marks run `FAILED`.
- Tool idempotency conflict marks tool call `FAILED` and run `FAILED`.
- GitHub rate limit returns retryable failure where response headers allow safe retry.
- GitHub 401/403 is non-retryable and marks run `FAILED`.

## 11. Data Model Additions

Add `tickets` projection:

- `tenant_id`
- `id`
- `run_id`
- `backend`
- `external_ticket_id`
- `external_url`
- `title`
- `body`
- `status`
- `severity`
- `labels_json`
- `assignee`
- `idempotency_marker`
- `created_at`
- `updated_at`

Add `fake_tickets`:

- `tenant_id`
- `id`
- `title`
- `body`
- `status`
- `severity`
- `labels_json`
- `assignee`
- `created_at`
- `updated_at`

Add `tool_idempotency_records`:

- `tenant_id`
- `operation_id`
- `tool_name`
- `status`
- `request_hash`
- `response_json`
- `created_at`
- `updated_at`

## 12. fga-gateway Placeholder

Until fga-gateway manifest validation and real OpenFGA checks are ready:

- Tool execution code must call a `ToolAuthorizer` interface.
- Local implementation may allow fake backend and deny GitHub backend unless explicitly configured.
- Interface must return `decisionId`, `allowed`, and `reason`.
- Tool-call projection must store `fga_decision_id` even if local placeholder returns `local-dev`.

This prevents tool code from being written in a way that bypasses authorization later.

## 13. Eval Requirements

Deterministic evals:

- Drafted ticket has title, body, severity, and labels.
- Approval is requested before GitHub mutation.
- Rejected approval produces no backend mutation.
- Fake ticket create is idempotent under retry.
- GitHub create searches for existing idempotency marker before creating.
- Agent does not close a ticket without explicit close intent.

LLM-as-judge eval:

- Ticket body is faithful to the user request.
- Judge output is stored as JSON artifact.
- CI fails non-zero when score is below configured threshold.

## 14. Testing Requirements

Java:

- Workflow test for approved fake-ticket completion.
- Workflow test for rejected approval causing no tool execution.
- Projection tests for ticket/tool/idempotency rows.

Python:

- Unit tests for schema validation.
- Unit tests for fake backend idempotency.
- Unit tests for GitHub idempotency marker generation and search behavior using a fake GitHub client.
- Agent graph tests for draft, approval, execute, summarize.

End-to-end:

- Local fake-ticket smoke test runs without external credentials.
- GitHub smoke test is opt-in and skipped unless all GitHub config is present.

## 15. Acceptance Criteria

- A fake-ticket run completes with no external credentials.
- The run requests approval before mutation by default.
- Rejecting approval leaves fake/GitHub backend unchanged.
- An approved fake-ticket run creates a ticket and records ticket lifecycle.
- A GitHub Issues run creates or reuses an issue by idempotency marker when GitHub config is present.
- Dashboard APIs can read run, approval, ticket, and tool-call projections.
- Eval gate fails when mutation happens before approval.
- Eval gate fails when final ticket schema is invalid.

## 16. Implementation Slices

1. Add ticket and idempotency schemas.
2. Add fake ticketing repository and service.
3. Add `ToolAuthorizer` placeholder interface.
4. Add Python support-ticket execute step for fake backend.
5. Wire Java workflow to call execute step after approval.
6. Add GitHub client abstraction and fake-client tests.
7. Add GitHub Issues backend.
8. Add deterministic eval suite.
9. Update dashboard/API read surfaces for ticket lifecycle.
