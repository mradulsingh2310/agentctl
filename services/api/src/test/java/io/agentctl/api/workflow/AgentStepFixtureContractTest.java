package io.agentctl.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class AgentStepFixtureContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executeFakeTicketFixturesMatchAgentStepProtocol() throws Exception {
        AgentStepRequest request = objectMapper.readValue(
                fixture("support-ticket-execute-fake-request.json"),
                AgentStepRequest.class);
        AgentStepResponse response = objectMapper.readValue(
                fixture("support-ticket-execute-fake-response.json"),
                AgentStepResponse.class);
        JsonNode ticket = objectMapper.valueToTree(response.output()).get("ticket");

        assertThat(request.agentId()).isEqualTo("support-ticket");
        assertThat(request.stepType()).isEqualTo("execute_ticket_workflow");
        assertThat(request.toolContext()).containsEntry("backend", "fake");
        assertThat(request.toolContext()).containsKey("approval");
        assertThat(request.toolContext()).containsKey("draft");
        assertThat(request.toolContext()).containsKey("operationBaseId");
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(ticket.get("backend").asText()).isEqualTo("fake");
        assertThat(ticket.get("externalTicketId").asText()).isEqualTo("fake_run_123");
        assertThat(response.toolCalls())
                .singleElement()
                .satisfies(toolCall -> {
                    assertThat(toolCall.toolName()).isEqualTo("fake_ticket.create");
                    assertThat(toolCall.operationId()).isEqualTo("run_123:approval_run_123:fake_ticket.create");
                    assertThat(toolCall.status()).isEqualTo("COMPLETED");
                });
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("..", "..", "contracts", "agent-step", name));
    }
}
