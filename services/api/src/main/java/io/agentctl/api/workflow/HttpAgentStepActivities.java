package io.agentctl.api.workflow;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class HttpAgentStepActivities implements AgentStepActivities {
    private final RestClient restClient;
    private final String protocolVersion;

    public HttpAgentStepActivities(RestClient.Builder restClientBuilder, AgentWorkerProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.stepTimeout());
        requestFactory.setReadTimeout(properties.stepTimeout());
        protocolVersion = properties.protocolVersion();
        restClient = restClientBuilder
                .baseUrl(trimTrailingSlash(properties.baseUrl()))
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public AgentStepResponse callAgentStep(AgentStepRequest request) {
        try {
            AgentStepResponse response = restClient.post()
                    .uri("/v1/agent-steps")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Agentctl-Tenant", request.tenantId())
                    .header("X-Agentctl-Run-Id", request.runId())
                    .body(request)
                    .retrieve()
                    .body(AgentStepResponse.class);
            if (response == null) {
                throw new IllegalStateException("Agent worker returned an empty response");
            }
            return response;
        } catch (HttpClientErrorException e) {
            return failedClientError(request, e);
        }
    }

    private AgentStepResponse failedClientError(AgentStepRequest request, HttpClientErrorException e) {
        return new AgentStepResponse(
                protocolVersion,
                request.stepId(),
                "FAILED",
                "Agent worker rejected the step request.",
                java.util.Map.of("responseBody", e.getResponseBodyAsString()),
                null,
                java.util.List.of(),
                null,
                new AgentStepError(
                        "AGENT_WORKER_HTTP_" + e.getStatusCode().value(),
                        e.getMessage(),
                        false));
    }

    private static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
