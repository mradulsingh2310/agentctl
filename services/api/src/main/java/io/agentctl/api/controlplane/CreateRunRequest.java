package io.agentctl.api.controlplane;

import jakarta.validation.constraints.NotBlank;

public record CreateRunRequest(
        @NotBlank String agentId,
        @NotBlank String input) {
}
