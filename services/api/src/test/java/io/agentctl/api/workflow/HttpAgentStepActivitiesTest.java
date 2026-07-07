package io.agentctl.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class HttpAgentStepActivitiesTest {
    private HttpServer server;
    private String baseUrl;
    private CapturedRequest capturedRequest;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/agent-steps", this::handleAgentStep);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsAgentStepWithTenantAndRunHeaders() throws Exception {
        HttpAgentStepActivities activities = new HttpAgentStepActivities(
                RestClient.builder(),
                new AgentWorkerProperties(baseUrl, "2026-07-07", Duration.ofSeconds(3)));
        AgentStepRequest request = objectMapper.readValue(
                fixture("support-ticket-draft-request.json"),
                AgentStepRequest.class);

        AgentStepResponse response = activities.callAgentStep(request);

        assertThat(capturedRequest.method()).isEqualTo("POST");
        assertThat(capturedRequest.tenant()).isEqualTo("tenant_a");
        assertThat(capturedRequest.runId()).isEqualTo("run_123");
        assertThat(objectMapper.readTree(capturedRequest.body()))
                .isEqualTo(objectMapper.readTree(fixture("support-ticket-draft-request.json")));
        assertThat(response.status()).isEqualTo("WAITING_FOR_APPROVAL");
        assertThat(response.approvalRequest().toolName()).isEqualTo("support_ticket.approve_draft");
        assertThat(response.modelUsage().provider()).isEqualTo("stub");
    }

    @Test
    void mapsAgentWorkerClientErrorToFailedStepResponse() {
        HttpAgentStepActivities activities = new HttpAgentStepActivities(
                RestClient.builder(),
                new AgentWorkerProperties(baseUrl, "2026-07-07", Duration.ofSeconds(3)));

        AgentStepResponse response = activities.callAgentStep(new AgentStepRequest(
                "2026-07-07",
                "tenant_a",
                "run_client_error",
                "support-ticket",
                "step_client_error",
                "client_error",
                "Create a support ticket",
                Map.of(),
                Map.of(),
                Map.of()));

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.stepId()).isEqualTo("step_client_error");
        assertThat(response.error().code()).isEqualTo("AGENT_WORKER_HTTP_400");
        assertThat(response.error().retryable()).isFalse();
    }

    private void handleAgentStep(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        capturedRequest = new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestHeaders().getFirst("X-Agentctl-Tenant"),
                exchange.getRequestHeaders().getFirst("X-Agentctl-Run-Id"),
                body);
        if (body.contains("\"stepType\":\"client_error\"")) {
            byte[] response = "{\"detail\":\"unsupported step\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, response.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(response);
            }
            return;
        }
        byte[] response = fixture("support-ticket-draft-response.json").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(response);
        }
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("..", "..", "contracts", "agent-step", name));
    }

    private record CapturedRequest(String method, String tenant, String runId, String body) {
    }
}
