package net.ximatai.muyun.spring.iam.web.security;

import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.web.RequestTenantVerifier;
import org.springframework.stereotype.Component;

/** Uses the same REFERENCE authorization as the global tenant navigator. */
@Component
public class BusinessRequestTenantVerifier implements RequestTenantVerifier {
    private final TenantService tenants;
    private final ActionExecutionPolicyService policies;

    public BusinessRequestTenantVerifier(TenantService tenants, ActionExecutionPolicyService policies) {
        this.tenants = tenants;
        this.policies = policies;
    }

    @Override
    public void verify(String tenantId) {
        var user = CurrentUserContext.currentUser().orElseThrow();
        if (user.system()) {
            policies.authorizeAction(TenantService.MODULE_ALIAS, PlatformAction.REFERENCE.executionPolicy(),
                    CurrentUserContext.currentUser());
            if (tenants.select(tenantId) == null) {
                throw new IllegalArgumentException("tenant reference denied");
            }
        } else if (!tenantId.equals(user.tenantId())) {
            throw new IllegalArgumentException("tenant identity mismatch");
        }
        tenants.verifyActiveTenant(tenantId);
    }
}
