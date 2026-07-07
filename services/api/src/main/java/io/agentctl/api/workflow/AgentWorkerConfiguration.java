package io.agentctl.api.workflow;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentWorkerProperties.class)
class AgentWorkerConfiguration {
}
