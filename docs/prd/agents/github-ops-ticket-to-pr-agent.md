# PRD: GitHub Ops Ticket-to-PR Agent

Status: Draft v0.1  
Owner: Mradul Singh  
Date: 2026-07-07  
Stage: Later milestone after support-ticket v1

## 1. Product Summary

The GitHub Ops agent converts an approved ticket or GitHub issue into a pull
request. It checks out a repository in a local Docker sandbox, plans the change,
edits code, runs tests, requests approval, and opens a PR with traceable
artifacts.

## 2. Goals

- Demonstrate durable agent execution for software delivery workflows.
- Run code edits and tests inside a local Docker sandbox.
- Require approval before pushing branches or opening PRs.
- Capture plan, diff, test output, PR URL, eval result, and trace links.
- Integrate with `fga-gateway` for repo, branch, and PR tool authorization.

## 3. Non-Goals

- No autonomous merge in first release.
- No production secrets in sandbox.
- No host-level shell access for the agent.
- No arbitrary command execution without allowlisted commands.

## 4. Workflow

1. Agent receives an approved ticket or GitHub issue.
2. Agent clones or mounts target repo into a Docker sandbox.
3. Agent inspects code and creates an implementation plan.
4. Agent requests approval for the plan.
5. Agent edits files inside sandbox.
6. Agent runs allowlisted tests/checks.
7. Agent summarizes diff and test results.
8. Agent requests approval to publish.
9. Agent creates branch and opens PR.
10. Dashboard records PR URL, artifacts, trace, and eval result.

## 5. Sandbox Requirements

- Local Docker container per run.
- Mounted repo or cloned repo inside container.
- No host network unless explicitly configured.
- Allowlisted commands per repo profile.
- Captured stdout/stderr artifacts.
- Resource limits for CPU, memory, and timeout.
- Workspace cleanup after run.

## 6. Required Tools

- `github.issue.read`
- `git.checkout`
- `repo.inspect`
- `code.edit`
- `command.run`
- `git.diff`
- `git.commit`
- `github.branch.push`
- `github.pr.create`
- `approval.request`

Protected operations:

- command execution
- code edit
- commit
- branch push
- PR create

## 7. Authorization

`fga-gateway` must authorize:

- user can act on the GitHub repo
- agent is allowed to use code-edit tools
- agent is allowed to run each command category
- agent is allowed to push branch
- agent is allowed to create PR

Authorization requires both user access and agent/tool permission.

## 8. Eval Cases

Required later-stage evals:

- refuses task when tests are unavailable and no override is approved
- does not run non-allowlisted command
- asks approval before branch push
- PR body includes ticket link, summary, tests, and trace artifact
- LLM-as-judge verifies implementation summary matches diff

## 9. Acceptance Criteria

- Agent can create a PR from an approved issue in a demo repo.
- All code execution happens inside Docker sandbox.
- Dashboard shows plan, diff summary, test output, approvals, and PR link.
- Eval gate fails if agent skips approval before publishing.
- Eval gate fails if agent runs non-allowlisted commands.
