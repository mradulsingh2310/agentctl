from fastapi import FastAPI, Header, HTTPException

from agentctl_agent_worker.health import health
from agentctl_agent_worker.protocol import (
    AgentStepApprovalRequest,
    AgentStepError,
    AgentStepModelUsage,
    AgentStepRequest,
    AgentStepResponse,
)
from agentctl_agent_worker.support_ticket import draft_support_ticket


app = FastAPI(title="agentctl agent worker")


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
    if request.stepType != "draft_ticket":
        return AgentStepResponse(
            stepId=request.stepId,
            status="FAILED",
            summary="Unsupported step type.",
            error=AgentStepError(
                code="UNSUPPORTED_STEP_TYPE",
                message=f"Unsupported stepType={request.stepType}",
                retryable=False,
            ),
        )

    draft = draft_support_ticket(request.input)
    return AgentStepResponse(
        stepId=request.stepId,
        status="WAITING_FOR_APPROVAL",
        summary=draft["summary"],
        output={"ticket": draft["ticket"]},
        approvalRequest=AgentStepApprovalRequest(
            toolName="support_ticket.approve_draft",
            question="Approve this ticket draft before any ticket backend mutation?",
        ),
        toolCalls=[],
        modelUsage=AgentStepModelUsage(
            provider="stub",
            model="stub",
            inputTokens=0,
            outputTokens=0,
        ),
    )
