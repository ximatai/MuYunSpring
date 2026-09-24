package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/** Shared record authorization for standard updates, physical deletes and soft deletes. */
final class MutationScopeSupport {
    private MutationScopeSupport() {}

    static DataScopeCriteriaResult resolve(CrudAbility<?> service, PlatformAction action, String id) {
        return resolve(service, action, id == null || id.isBlank() ? List.of() : List.of(id));
    }

    static DataScopeCriteriaResult resolve(CrudAbility<?> service, PlatformAction action, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return DataScopeCriteriaResult.unrestricted(Criteria.of());
        }
        var verified = VerifiedMutationScopeExecutor.current(service, action, ids);
        if (verified.isPresent()) return verified.get();
        if (service instanceof DataScopeAbility<?> scoped) {
            var policy = ActionExecutionContextHolder.current()
                    .filter(context -> context.moduleAlias().equals(service.getModuleAlias()))
                    .map(ActionExecutionContext::actionPolicy)
                    .orElseGet(action::executionPolicy);
            return scoped.requireRecordScopeResult(policy, ids);
        }
        return DataScopeCriteriaResult.unrestricted(Criteria.of());
    }

    static <R> R withTenantScope(DataScopeCriteriaResult scope, Supplier<R> mutation) {
        if (scope != null && scope.crossTenant()) {
            try (var ignored = TenantContext.bypassTenantFilter("data scope allows cross-tenant mutation")) {
                return mutation.get();
            }
        }
        return mutation.get();
    }
}
