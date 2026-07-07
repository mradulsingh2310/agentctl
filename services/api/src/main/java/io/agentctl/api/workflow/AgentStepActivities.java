package io.agentctl.api.workflow;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface AgentStepActivities {
    AgentStepResponse callAgentStep(AgentStepRequest request);
}
