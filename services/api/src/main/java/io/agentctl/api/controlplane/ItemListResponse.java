package io.agentctl.api.controlplane;

import java.util.List;

public record ItemListResponse<T>(List<T> items) {
}
