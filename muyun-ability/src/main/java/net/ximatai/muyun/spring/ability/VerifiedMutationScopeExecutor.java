package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Binds one verified aggregate-owned scope while preserving the service's normal polymorphic entry point. */
public final class VerifiedMutationScopeExecutor {
    private static final ThreadLocal<Deque<VerifiedMutationScope>> CURRENT =
            ThreadLocal.withInitial(ArrayDeque::new);

    private VerifiedMutationScopeExecutor() {
    }

    public static <R> R execute(CrudAbility<?> service, PlatformAction action, Collection<String> recordIds,
                                VerifiedMutationScope scope, Supplier<R> mutation) {
        java.util.Objects.requireNonNull(service, "service must not be null");
        java.util.Objects.requireNonNull(scope, "scope must not be null");
        java.util.Objects.requireNonNull(mutation, "mutation must not be null");
        scope.claim(service, java.util.Objects.requireNonNull(action, "action must not be null"),
                normalize(recordIds));
        Deque<VerifiedMutationScope> scopes = CURRENT.get();
        scopes.addLast(scope);
        try {
            if (scope.criteriaResult().crossTenant()) {
                try (TenantContext.Scope ignored = TenantContext.bypassTenantFilter(
                        "verified aggregate mutation scope allows cross-tenant access")) {
                    return mutation.get();
                }
            }
            return mutation.get();
        } finally {
            VerifiedMutationScope removed = scopes.pollLast();
            if (removed != scope) {
                scopes.clear();
                CURRENT.remove();
                throw new IllegalStateException("verified mutation scopes closed out of order");
            }
            if (scopes.isEmpty()) CURRENT.remove();
        }
    }

    static Optional<DataScopeCriteriaResult> current(CrudAbility<?> service, PlatformAction action,
                                                     Collection<String> recordIds) {
        Deque<VerifiedMutationScope> scopes = CURRENT.get();
        if (scopes.isEmpty()) {
            CURRENT.remove();
            return Optional.empty();
        }
        Set<String> normalized = normalize(recordIds);
        boolean serviceBound = false;
        for (Iterator<VerifiedMutationScope> iterator = scopes.descendingIterator(); iterator.hasNext(); ) {
            VerifiedMutationScope scope = iterator.next();
            if (!scope.belongsTo(service)) continue;
            serviceBound = true;
            if (scope.matches(service, action, normalized)) {
                return Optional.of(scope.criteriaResult());
            }
        }
        if (serviceBound) {
            throw new IllegalStateException("mutation does not match its verified service, operation, or record");
        }
        return Optional.empty();
    }

    private static Set<String> normalize(Collection<String> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) return Set.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        recordIds.stream().filter(id -> id != null && !id.isBlank()).map(String::trim)
                .forEach(normalized::add);
        return Set.copyOf(normalized);
    }
}
