package io.agentctl.api.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentctl.temporal")
public record AgentctlTemporalProperties(
        boolean enabled,
        String address,
        String namespace,
        String taskQueue) {
}
