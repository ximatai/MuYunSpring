package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

public interface TenantActiveScopedAbility<T extends EntityContract> extends MutationScopeAbility<T>, ActiveTenantVerifier {
    @Override
    default void requireMutationContext(T entity) {
        requireActiveTenantMutationContext();
    }

    default String requireActiveTenantMutationContext() {
        String tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new PlatformAccessDeniedException(
                        getModuleAlias() + " management requires tenant context", ErrorScope.module(getModuleAlias())));
        verifyActiveTenant(tenantId);
        return tenantId;
    }

}
