package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

public interface SystemManagedAbility<T extends EntityContract> extends MutationScopeAbility<T> {
    @Override
    default void requireMutationContext(T entity) {
        requireSystemMutationContext();
    }

    default void requireSystemMutationContext() {
        if (!TenantContext.isSystem()) {
            throw new PlatformAccessDeniedException(
                    getModuleAlias() + " management requires system context", ErrorScope.module(getModuleAlias()));
        }
    }
}
