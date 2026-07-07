package io.agentctl.api.workflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

@Configuration
@EnableConfigurationProperties(AgentctlTemporalProperties.class)
@ConditionalOnProperty(name = "agentctl.temporal.enabled", havingValue = "true", matchIfMissing = true)
class TemporalClientConfiguration {
    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs workflowServiceStubs(AgentctlTemporalProperties properties) {
        WorkflowServiceStubsOptions options = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(properties.address())
                .build();
        return WorkflowServiceStubs.newServiceStubs(options);
    }

    @Bean
    WorkflowClient workflowClient(
            WorkflowServiceStubs workflowServiceStubs,
            AgentctlTemporalProperties properties) {
        WorkflowClientOptions options = WorkflowClientOptions.newBuilder()
                .setNamespace(properties.namespace())
                .build();
        return WorkflowClient.newInstance(workflowServiceStubs, options);
    }
}
