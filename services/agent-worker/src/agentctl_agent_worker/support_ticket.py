from typing import Any, TypedDict

from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langgraph.graph import END, START, StateGraph

from agentctl_agent_worker.fake_ticketing import (
    FakeTicketTool,
    ToolExecutionContext,
    build_fake_ticket_tool_from_env,
)
from agentctl_agent_worker.protocol import (
    AgentStepApprovalRequest,
    AgentStepError,
    AgentStepModelUsage,
    AgentStepRequest,
    AgentStepResponse,
)


class DraftState(TypedDict, total=False):
    input: str
    ticket: dict[str, Any]
    summary: str


def draft_ticket_node(state: DraftState) -> DraftState:
    user_input = state["input"].strip()
    lower = user_input.lower()
    title = infer_title(lower)
    severity = "high" if any(marker in lower for marker in ["500", "urgent", "down", "outage"]) else "medium"
    labels = ["bug"]
    if "checkout" in lower:
        labels.append("checkout")
    if "login" in lower:
        labels.append("login")
    if len(labels) == 1:
        labels.append("support")

    body = normalize_body(user_input)
    ticket = {
        "title": title,
        "body": body,
        "severity": severity,
        "labels": labels,
    }
    return {
        "input": user_input,
        "ticket": ticket,
        "summary": f"Drafted a {severity} support ticket: {title}.",
    }


def build_graph():
    graph = StateGraph(DraftState)
    graph.add_node("draft_ticket", draft_ticket_node)
    graph.add_edge(START, "draft_ticket")
    graph.add_edge("draft_ticket", END)
    return graph.compile()


support_ticket_graph = build_graph()


def draft_support_ticket(user_input: str) -> dict[str, Any]:
    # Keep the LangChain model boundary present while M4 stays deterministic.
    model = GenericFakeChatModel(messages=iter(["draft-ticket"]))
    model.invoke(user_input)
    result = support_ticket_graph.invoke({"input": user_input})
    return {
        "ticket": result["ticket"],
        "summary": result["summary"],
    }


def handle_support_ticket_step(
    request: AgentStepRequest | dict[str, Any],
    fake_ticket_tool: FakeTicketTool | None = None,
) -> AgentStepResponse:
    step_request = AgentStepRequest.model_validate(request)
    if step_request.stepType == "draft_ticket":
        draft = draft_support_ticket(step_request.input)
        return AgentStepResponse(
            stepId=step_request.stepId,
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
    if step_request.stepType != "execute_ticket_workflow":
        return AgentStepResponse(
            stepId=step_request.stepId,
            status="FAILED",
            summary="Unsupported step type.",
            error=AgentStepError(
                code="UNSUPPORTED_STEP_TYPE",
                message=f"Unsupported stepType={step_request.stepType}",
                retryable=False,
            ),
        )
    return execute_ticket_workflow(step_request, fake_ticket_tool or default_fake_ticket_tool())


def execute_ticket_workflow(step_request: AgentStepRequest, fake_ticket_tool: FakeTicketTool) -> AgentStepResponse:
    tool_context = step_request.toolContext
    backend = tool_context.get("backend", "fake")
    if backend != "fake":
        return failed_response(
            step_request,
            "UNSUPPORTED_TICKET_BACKEND",
            f"Unsupported ticket backend: {backend}",
        )
    for key in ["approval", "draft", "operationBaseId"]:
        if key not in tool_context:
            return failed_response(
                step_request,
                "MISSING_APPROVAL_CONTEXT",
                f"execute_ticket_workflow requires toolContext.{key}",
            )

    result = fake_ticket_tool.create_ticket(ToolExecutionContext(
        tenant_id=step_request.tenantId,
        run_id=step_request.runId,
        backend=backend,
        approval=tool_context["approval"],
        draft=tool_context["draft"],
        operation_base_id=tool_context["operationBaseId"],
    ))
    if result.status != "COMPLETED":
        error = result.error or {
            "code": "TOOL_EXECUTION_FAILED",
            "message": "Fake ticket tool failed.",
            "retryable": False,
        }
        return failed_response(step_request, error["code"], error["message"], error["retryable"])

    return AgentStepResponse(
        stepId=step_request.stepId,
        status="COMPLETED",
        summary=f"Created fake ticket {result.ticket['externalTicketId']}.",
        output={"ticket": result.ticket},
        approvalRequest=None,
        toolCalls=[result.tool_call],
        modelUsage=AgentStepModelUsage(
            provider="stub",
            model="stub",
            inputTokens=0,
            outputTokens=0,
        ),
        error=None,
    )


def failed_response(
    step_request: AgentStepRequest,
    code: str,
    message: str,
    retryable: bool = False,
) -> AgentStepResponse:
    return AgentStepResponse(
        stepId=step_request.stepId,
        status="FAILED",
        summary=message,
        error=AgentStepError(code=code, message=message, retryable=retryable),
    )


def default_fake_ticket_tool() -> FakeTicketTool:
    return build_fake_ticket_tool_from_env()


def infer_title(lower_input: str) -> str:
    if "checkout" in lower_input and "500" in lower_input:
        return "Checkout fails with HTTP 500"
    if "login" in lower_input and any(marker in lower_input for marker in ["fail", "failure", "error"]):
        return "Login fails"
    words = lower_input.split()
    compact = " ".join(words[:8])
    return compact.capitalize() if compact else "Support request"


def normalize_body(user_input: str) -> str:
    stripped = user_input.strip().rstrip(".")
    if stripped.lower().startswith("create a support ticket for "):
        stripped = stripped[len("Create a support ticket for ") :]
    return f"User reported {stripped}."
