package io.agentctl.api.workflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.temporal.client.WorkflowClient;

@Configuration
@EnableConfigurationProperties(AgentctlTemporalProperties.class)
class RunWorkflowGatewayConfiguration {
    @Bean
    @ConditionalOnProperty(name = "agentctl.temporal.enabled", havingValue = "true", matchIfMissing = true)
    RunWorkflowGateway temporalRunWorkflowGateway(
            WorkflowClient workflowClient,
            AgentctlTemporalProperties properties) {
        return new TemporalRunWorkflowGateway(workflowClient, properties.taskQueue());
    }

    @Bean
    @ConditionalOnProperty(name = "agentctl.temporal.enabled", havingValue = "false")
    RunWorkflowGateway noopRunWorkflowGateway() {
        return new NoopRunWorkflowGateway();
    }
}
