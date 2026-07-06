const runs = [
  {
    id: "run_support_001",
    agent: "support-ticket",
    status: "WAITING_FOR_APPROVAL",
    tool: "github_issue.create",
    trace: "trace-7f31",
  },
  {
    id: "run_eval_014",
    agent: "support-ticket",
    status: "COMPLETED",
    tool: "fake_ticket.update",
    trace: "trace-bc22",
  },
  {
    id: "run_incident_seed",
    agent: "incident-agent",
    status: "FAILED",
    tool: "grafana.query",
    trace: "trace-a018",
  },
];

const statusClass: Record<string, string> = {
  COMPLETED: "ok",
  FAILED: "failed",
  WAITING_FOR_APPROVAL: "waiting",
};

export default function Page() {
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <strong>agentctl</strong>
          <span>Durable LangGraph control plane</span>
        </div>
        <nav className="nav" aria-label="Dashboard">
          <a href="#" aria-current="page">
            Runs
          </a>
          <a href="#">Pending approvals</a>
          <a href="#">Tool calls</a>
          <a href="#">Model usage</a>
          <a href="#">Eval gates</a>
          <a href="#">Trace links</a>
        </nav>
      </aside>

      <main className="main">
        <header className="topbar">
          <div>
            <h1>Runs</h1>
            <p>
              Temporal is execution truth. No authoritative WAL is stored in
              agentctl v1; the dashboard reads product projections and trace
              correlation state.
            </p>
          </div>
        </header>

        <section className="status-grid" aria-label="Platform status">
          <div className="status-item">
            <span>Running agents</span>
            <strong>3</strong>
          </div>
          <div className="status-item">
            <span>Pending approvals</span>
            <strong>1</strong>
          </div>
          <div className="status-item">
            <span>Eval gates</span>
            <strong>2 passing</strong>
          </div>
          <div className="status-item">
            <span>Model usage</span>
            <strong>local</strong>
          </div>
        </section>

        <div className="content-grid">
          <section className="panel" aria-labelledby="runs-heading">
            <div className="panel-header">
              <h2 id="runs-heading">Runs</h2>
              <span>projection view</span>
            </div>
            <table className="table">
              <thead>
                <tr>
                  <th>Run</th>
                  <th>Agent</th>
                  <th>Status</th>
                  <th>Tool calls</th>
                  <th>Trace links</th>
                </tr>
              </thead>
              <tbody>
                {runs.map((run) => (
                  <tr key={run.id}>
                    <td>{run.id}</td>
                    <td>{run.agent}</td>
                    <td>
                      <span className={`tag ${statusClass[run.status]}`}>
                        {run.status}
                      </span>
                    </td>
                    <td>{run.tool}</td>
                    <td>{run.trace}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>

          <div className="stack">
            <section className="panel" aria-labelledby="approvals-heading">
              <div className="panel-header">
                <h2 id="approvals-heading">Pending approvals</h2>
                <span>Temporal signal required</span>
              </div>
              <div className="list">
                <div className="list-row">
                  <strong>github_issue.create for run_support_001</strong>
                  <span>Awaiting human approval before side effect.</span>
                </div>
              </div>
            </section>

            <section className="panel" aria-labelledby="eval-heading">
              <div className="panel-header">
                <h2 id="eval-heading">Eval gates</h2>
                <span>LLM-as-judge artifacts</span>
              </div>
              <div className="list">
                <div className="list-row">
                  <strong>approval-before-side-effect</strong>
                  <span>Passing threshold: 1.00</span>
                </div>
                <div className="list-row">
                  <strong>ticket-schema-validity</strong>
                  <span>Passing threshold: 0.95</span>
                </div>
              </div>
            </section>
          </div>
        </div>
      </main>
    </div>
  );
}
