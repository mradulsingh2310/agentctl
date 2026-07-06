package io.agentctl.api.controlplane;

import jakarta.validation.constraints.NotBlank;

public record ApprovalDecisionRequest(
        @NotBlank String actorId,
        @NotBlank String reason) {
}
