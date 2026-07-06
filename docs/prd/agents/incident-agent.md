# PRD: Incident Agent

Status: Draft v0.1  
Owner: Mradul Singh  
Date: 2026-07-07  
Stage: Later milestone after support-ticket v1

## 1. Product Summary

The incident agent investigates seeded telemetry in the local `agentctl`
observability stack, correlates symptoms with runbooks, proposes root cause, and
produces an incident timeline with recommended actions. It exists to prove that
`agentctl` can operate agents over production-style telemetry, not only ticket
workflows.

## 2. Goals

- Use the same OTel/Grafana stack shipped by `agentctl`.
- Ingest or query seeded traces, logs, metrics, and runbooks.
- Produce grounded incident summaries with trace/log references.
- Request approval before any remediation action.
- Record RCA artifacts, cost, latency, and eval outcomes.

## 3. Non-Goals

- No destructive remediation in the first incident-agent release.
- No PagerDuty/Opsgenie integration in first release.
- No live production cluster dependency.
- No broad observability vendor support until the local demo stack is complete.

## 4. Data Sources

V1 demo data:

- seeded OpenTelemetry traces
- seeded application logs
- seeded Prometheus metrics
- runbook markdown files
- service ownership metadata

Later integrations:

- Grafana API
- Prometheus API
- Loki API
- Tempo/Jaeger API
- cloud provider events

## 5. Agent Workflow

1. User starts an incident investigation run.
2. Agent loads incident signal: alert, metric anomaly, trace IDs, or service name.
3. Agent queries telemetry sources.
4. Agent searches runbooks and recent run history.
5. Agent builds timeline.
6. Agent proposes likely root cause with evidence.
7. Agent proposes safe next actions.
8. Any remediation action requires approval.
9. Agent writes incident artifact and eval metadata.

## 6. Required Tools

- `metrics.query`
- `traces.search`
- `traces.get`
- `logs.search`
- `runbook.search`
- `service.get_owner`
- `incident.write_timeline`
- `incident.propose_action`
- `approval.request`

Protected tools:

- any action marked as remediation
- any write to incident state

## 7. Dashboard Requirements

Incident run detail must show:

- alert/input
- timeline
- evidence table
- runbook citations
- suggested root cause
- proposed actions
- approval state
- trace/log links
- eval result

## 8. Eval Cases

Required later-stage evals:

- correct root cause for seeded database latency incident
- correct root cause for seeded downstream 500 incident
- no remediation without approval
- cites at least one trace/log/runbook source
- distinguishes symptom from root cause
- LLM-as-judge scores evidence grounding above configured threshold

## 9. Acceptance Criteria

- Incident agent can run fully against seeded local telemetry.
- RCA artifact includes timeline, evidence, root cause, and action plan.
- Dashboard links evidence to trace/log/runbook sources.
- Eval gate fails for ungrounded RCA.
- Remediation proposal waits for human approval.
