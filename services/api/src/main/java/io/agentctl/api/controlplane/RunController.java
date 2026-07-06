package io.agentctl.api.controlplane;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class RunController {
    private final ControlPlaneService controlPlaneService;

    public RunController(ControlPlaneService controlPlaneService) {
        this.controlPlaneService = controlPlaneService;
    }

    @PostMapping("/runs")
    public RunResponse createRun(
            @RequestHeader(value = TenantContext.HEADER, required = false) String tenantHeader,
            @Valid @RequestBody CreateRunRequest request) {
        return controlPlaneService.createRun(TenantContext.resolve(tenantHeader), request);
    }

    @GetMapping("/runs")
    public ItemListResponse<RunResponse> listRuns(
            @RequestHeader(value = TenantContext.HEADER, required = false) String tenantHeader) {
        return controlPlaneService.listRuns(TenantContext.resolve(tenantHeader));
    }

    @GetMapping("/runs/{runId}")
    public RunResponse getRun(
            @RequestHeader(value = TenantContext.HEADER, required = false) String tenantHeader,
            @PathVariable String runId) {
        return controlPlaneService.getRun(TenantContext.resolve(tenantHeader), runId);
    }

    @GetMapping("/runs/{runId}/audit")
    public ItemListResponse<AuditEventResponse> listRunAuditEvents(
            @RequestHeader(value = TenantContext.HEADER, required = false) String tenantHeader,
            @PathVariable String runId) {
        return controlPlaneService.listRunAuditEvents(TenantContext.resolve(tenantHeader), runId);
    }
}
