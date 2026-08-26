package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

import java.util.Optional;
import java.util.function.Supplier;

public final class MutationTenantScopeExecutor {
    private MutationTenantScopeExecutor() {
    }

    public static <T extends EntityContract, R> R forCreate(Object owner, T record, Supplier<R> action) {
        return run(resolver(owner)
                .flatMap(resolver -> resolver.tenantIdForCreate(record)), action);
    }

    /**
     * Runs a mutation in the tenant scope already resolved by a trusted page-selection contract.
     * A {@code null} tenant deliberately means system scope; callers must not fall back to a
     * browser record-derived resolver after selecting that branch.
     */
    public static <R> R forAuthoritativeTenantScope(String tenantId, Supplier<R> action) {
        return run(Optional.ofNullable(tenantId), action);
    }

    public static <T extends EntityContract, R> R forUpdate(Object owner,
                                                            String id,
                                                            T record,
                                                            Supplier<R> action) {
        return run(resolver(owner)
                .flatMap(resolver -> resolver.tenantIdForUpdate(id, record)), action);
    }

    public static <R> R forExistingRecord(Object owner, String id, Supplier<R> action) {
        return run(existingRecordTenantId(owner, id), action);
    }

    private static Optional<String> existingRecordTenantId(Object owner, String id) {
        return resolver(owner)
                .flatMap(resolver -> resolver.tenantIdForExistingRecord(id));
    }

    private static <R> R run(Optional<String> tenantId, Supplier<R> action) {
        if (!TenantContext.isSystem() || tenantId.isEmpty()) {
            return action.get();
        }
        try (TenantContext.Scope ignored = TenantContext.use(tenantId.get())) {
            return action.get();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends EntityContract> Optional<MutationTenantScopeResolver<T>> resolver(Object owner) {
        if (!TenantContext.isSystem() || !(owner instanceof MutationTenantScopeResolver<?> resolver)) {
            return Optional.empty();
        }
        return Optional.of((MutationTenantScopeResolver<T>) resolver);
    }
}
