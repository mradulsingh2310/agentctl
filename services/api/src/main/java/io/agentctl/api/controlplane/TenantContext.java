package io.agentctl.api.controlplane;

import org.springframework.util.StringUtils;

final class TenantContext {
    static final String HEADER = "X-Agentctl-Tenant";
    static final String LOCAL_DEV_TENANT = "local-dev";

    private TenantContext() {
    }

    static String resolve(String tenantHeader) {
        if (!StringUtils.hasText(tenantHeader)) {
            return LOCAL_DEV_TENANT;
        }
        return tenantHeader.trim();
    }
}
