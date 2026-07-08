from fastapi import FastAPI, Header, HTTPException

from agentctl_agent_worker.health import health
from agentctl_agent_worker.protocol import (
    AgentStepError,
    AgentStepRequest,
    AgentStepResponse,
)
from agentctl_agent_worker.support_ticket import default_fake_ticket_tool, handle_support_ticket_step


app = FastAPI(title="agentctl agent worker")
app.state.fake_ticket_tool = None


@app.get("/health")
def read_health() -> dict[str, str]:
    return health()


@app.post("/v1/agent-steps", response_model=AgentStepResponse)
def run_agent_step(
    request: AgentStepRequest,
    x_agentctl_tenant: str = Header(alias="X-Agentctl-Tenant"),
    x_agentctl_run_id: str = Header(alias="X-Agentctl-Run-Id"),
) -> AgentStepResponse:
    if x_agentctl_tenant != request.tenantId:
        raise HTTPException(status_code=400, detail="Tenant header does not match request body")
    if x_agentctl_run_id != request.runId:
        raise HTTPException(status_code=400, detail="Run header does not match request body")
    if request.agentId != "support-ticket":
        return AgentStepResponse(
            stepId=request.stepId,
            status="FAILED",
            summary="Unsupported agent id.",
            error=AgentStepError(
                code="UNSUPPORTED_AGENT",
                message=f"No agent is registered for agentId={request.agentId}",
                retryable=False,
            ),
        )
    return handle_support_ticket_step(request, fake_ticket_tool=fake_ticket_tool())


def fake_ticket_tool():
    if app.state.fake_ticket_tool is None:
        app.state.fake_ticket_tool = default_fake_ticket_tool()
    return app.state.fake_ticket_tool
