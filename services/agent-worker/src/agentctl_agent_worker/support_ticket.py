from typing import Any, TypedDict

from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langgraph.graph import END, START, StateGraph


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
