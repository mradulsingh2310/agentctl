# PRD: Support Ticket Agent

Status: Draft v0.1  
Owner: Mradul Singh  
Date: 2026-07-07  
Parent: `agentctl`

## 1. Product Summary

The support-ticket agent is the v1 reference agent for `agentctl`. It proves the
core platform by turning a user request into a ticket workflow with model
reasoning, human approval, tool authorization, deterministic fake ticketing, real
GitHub Issues integration, cost tracking, traceability, and eval gates.

## 2. Goals

- Provide the first complete end-to-end `agentctl` demo.
- Run without external credentials using built-in fake ticketing.
- Run against real GitHub Issues when a token and repository are configured.
- Exercise human approval before externally visible issue changes.
- Exercise fga-gateway preflight for protected tools.
- Demonstrate tool idempotency as tool-owned behavior, not runtime-owned duplication.
- Provide eval cases for approval, schema, authorization, and issue lifecycle behavior.

## 3. Non-Goals

- No Jira integration in v1.
- No Slack/email approval channel in v1.
- No generalized helpdesk product.
- No autonomous GitHub PR creation in this agent; that belongs to the GitHub Ops ticket-to-PR agent.

## 4. User Journey

1. User submits: "Create a bug ticket for checkout failing with HTTP 500."
2. Agent drafts a normalized ticket with severity, title, body, labels, and owner hints.
3. Agent requests human approval.
4. Approver reviews context in dashboard or CLI.
5. On approval, agent calls fake ticketing or GitHub Issues tools.
6. Agent returns ticket/issue URL and lifecycle summary.
7. Dashboard shows timeline, approval, model calls, tool calls, cost, and trace links.

## 5. Ticket Backends

### 5.1 Fake Ticketing Backend

Purpose:

- deterministic local demo
- reliable integration tests
- eval fixtures
- idempotency demonstration

Capabilities:

- create ticket
- update title/body
- add labels
- assign owner
- add comment
- close ticket
- search by idempotency key

Storage:

- Postgres tables owned by `agentctl`

### 5.2 GitHub Issues Backend

Purpose:

- real-world external side effect demo
- developer-facing OSS credibility

Required configuration:

- `GITHUB_TOKEN`
- `GITHUB_OWNER`
- `GITHUB_REPO`

Capabilities:

- create issue
- edit issue title/body
- add labels
- assign users
- add comments
- close issue
- detect previous issue by idempotency marker

Idempotency:

- GitHub does not provide a native issue-create idempotency key.
- The GitHub tool must implement idempotency by writing an idempotency marker into issue body or metadata label and searching before retry.
- `agentctl` passes a stable operation ID, but the GitHub tool owns reconciliation.

## 6. Tool Contract

Tools:

- `draft_ticket`
- `request_ticket_approval`
- `fake_ticket.create`
- `fake_ticket.update`
- `fake_ticket.label`
- `fake_ticket.assign`
- `fake_ticket.comment`
- `fake_ticket.close`
- `github_issue.create`
- `github_issue.update`
- `github_issue.label`
- `github_issue.assign`
- `github_issue.comment`
- `github_issue.close`

Protected operations:

- all fake ticket mutation tools
- all GitHub issue mutation tools

Every protected operation must call fga-gateway before execution.

## 7. Approval Policy

Approval is required before:

- creating a real GitHub issue
- closing a real GitHub issue
- assigning a real GitHub issue
- applying labels that change priority or severity

Approval may be skipped for fake-ticketing smoke tests only when the eval case or
demo config explicitly marks the backend as fake and the action as non-external.

## 8. Agent Behavior

The LangGraph graph must include nodes for:

- parse user request
- classify ticket type
- draft ticket
- validate ticket schema
- request approval
- execute backend workflow
- summarize result

The agent must not mutate GitHub until approval is granted.

## 9. Data Model Additions

Ticket projection fields:

- `tenant_id`
- `run_id`
- `backend`
- `external_ticket_id`
- `title`
- `status`
- `severity`
- `labels`
- `assignee`
- `idempotency_marker`
- `created_at`
- `updated_at`

Tool-call projection must include backend, tool name, operation ID, outcome, and
external URL when available.

## 10. Eval Cases

V1 required evals:

- user asks for a bug ticket and agent produces valid schema
- agent requests approval before GitHub mutation
- rejected approval does not mutate backend
- fake ticket create is idempotent under retry
- GitHub issue create searches for an existing idempotency marker before creating
- agent does not close an issue without explicit close intent
- LLM-as-judge confirms ticket body is faithful to user request

## 11. Acceptance Criteria

- A fake-ticket run completes with no external credentials.
- A GitHub Issues run completes when GitHub config is provided.
- Dashboard shows ticket lifecycle and approval history.
- CLI can approve or reject the pending ticket action.
- Eval gate fails if GitHub mutation happens before approval.
- Eval gate fails if final ticket schema is invalid.
- Tool calls emit OTel spans and fga-gateway authz checks.
