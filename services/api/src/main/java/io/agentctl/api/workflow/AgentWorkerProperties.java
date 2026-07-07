package io.agentctl.api.workflow;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentctl.agent-worker")
public record AgentWorkerProperties(
        String baseUrl,
        String protocolVersion,
        Duration stepTimeout) {
    public AgentWorkerProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://agentctl-agent-worker:8090";
        }
        if (protocolVersion == null || protocolVersion.isBlank()) {
            protocolVersion = "2026-07-07";
        }
        if (stepTimeout == null) {
            stepTimeout = Duration.ofSeconds(30);
        }
    }
}
