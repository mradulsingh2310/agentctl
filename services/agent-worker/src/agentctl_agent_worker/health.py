def health() -> dict[str, str]:
    return {
        "service": "agentctl-agent-worker",
        "status": "UP",
        "agent_runtime": "langgraph",
        "model_boundary": "langchain",
    }
