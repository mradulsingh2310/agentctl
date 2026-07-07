from typing import Any

from pydantic import BaseModel, Field


PROTOCOL_VERSION = "2026-07-07"


class AgentStepRequest(BaseModel):
    protocolVersion: str
    tenantId: str
    runId: str
    agentId: str
    stepId: str
    stepType: str
    input: str
    modelProfile: dict[str, Any] = Field(default_factory=dict)
    toolContext: dict[str, Any] = Field(default_factory=dict)
    traceContext: dict[str, Any] = Field(default_factory=dict)


class AgentStepApprovalRequest(BaseModel):
    toolName: str
    question: str


class AgentStepModelUsage(BaseModel):
    provider: str
    model: str
    inputTokens: int
    outputTokens: int


class AgentStepError(BaseModel):
    code: str
    message: str
    retryable: bool


class AgentStepResponse(BaseModel):
    protocolVersion: str = PROTOCOL_VERSION
    stepId: str
    status: str
    summary: str
    output: dict[str, Any] = Field(default_factory=dict)
    approvalRequest: AgentStepApprovalRequest | None = None
    toolCalls: list[dict[str, Any]] = Field(default_factory=list)
    modelUsage: AgentStepModelUsage | None = None
    error: AgentStepError | None = None
