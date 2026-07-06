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
@RequestMapping("/api/approvals")
public class ApprovalController {
    private final ControlPlaneService controlPlaneService;

    public ApprovalController(ControlPlaneService controlPlaneService) {
        this.controlPlaneService = controlPlaneService;
    }

    @GetMapping("/pending")
    public ItemListResponse<ApprovalResponse> listPendingApprovals(
            @RequestHeader(value = TenantContext.HEADER, required = false) String tenantHeader) {
        return controlPlaneService.listPendingApprovals(TenantContext.resolve(tenantHeader));
    }

    @PostMapping("/{approvalId}/approve")
    public ApprovalResponse approveApproval(
            @RequestHeader(value = TenantContext.HEADER, required = false) String tenantHeader,
            @PathVariable String approvalId,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        return controlPlaneService.approveApproval(TenantContext.resolve(tenantHeader), approvalId, request);
    }

    @PostMapping("/{approvalId}/reject")
    public ApprovalResponse rejectApproval(
            @RequestHeader(value = TenantContext.HEADER, required = false) String tenantHeader,
            @PathVariable String approvalId,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        return controlPlaneService.rejectApproval(TenantContext.resolve(tenantHeader), approvalId, request);
    }
}
