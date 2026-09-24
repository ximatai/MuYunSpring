package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.identity.ActingContext;
import net.ximatai.muyun.spring.common.identity.ActingContextHolder;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformAction;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opaque proof that a concrete service mutation was checked against an effective data policy.
 * Only ability-level scope resolution can issue this one-shot proof.
 */
public final class VerifiedMutationScope {
    private final MutationServiceIdentity service;
    private final PlatformAction action;
    private final Set<String> recordIds;
    private final DataScopeCriteriaResult criteriaResult;
    private final AtomicBoolean claimed = new AtomicBoolean();
    private final Optional<CurrentUser> actor = CurrentUserContext.currentUser();
    private final Optional<ActingContext> acting = ActingContextHolder.current();
    private final Optional<String> tenant = TenantContext.currentTenantId();
    private final boolean tenantContext = TenantContext.hasContext();
    private final boolean system = TenantContext.isSystem();
    private final boolean tenantBypass = TenantContext.tenantFilterBypassed();

    VerifiedMutationScope(CrudAbility<?> service, PlatformAction action, Set<String> recordIds,
                          DataScopeCriteriaResult criteriaResult) {
        this.service = Objects.requireNonNull(service, "service must not be null").mutationServiceIdentity();
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.recordIds = Set.copyOf(recordIds);
        this.criteriaResult = Objects.requireNonNull(criteriaResult,
                "criteriaResult must not be null");
    }

    DataScopeCriteriaResult criteriaResult() {
        return criteriaResult;
    }

    boolean matches(CrudAbility<?> candidate, PlatformAction candidateAction, Set<String> candidateIds) {
        return matches(candidate, candidateAction, candidateIds, false);
    }

    boolean matchesExecution(CrudAbility<?> candidate, PlatformAction candidateAction, Set<String> candidateIds) {
        return matches(candidate, candidateAction, candidateIds, criteriaResult.crossTenant());
    }

    private boolean matches(CrudAbility<?> candidate, PlatformAction candidateAction, Set<String> candidateIds,
                            boolean authorizedBypass) {
        return belongsTo(candidate) && action == candidateAction && recordIds.equals(candidateIds)
                && actor.equals(CurrentUserContext.currentUser()) && acting.equals(ActingContextHolder.current())
                && tenant.equals(TenantContext.currentTenantId()) && tenantContext == TenantContext.hasContext()
                && system == TenantContext.isSystem()
                && (tenantBypass || authorizedBypass) == TenantContext.tenantFilterBypassed();
    }

    boolean belongsTo(CrudAbility<?> candidate) {
        return service.equals(candidate.mutationServiceIdentity());
    }

    void claim(CrudAbility<?> candidate, PlatformAction candidateAction, Set<String> candidateIds) {
        if (!matches(candidate, candidateAction, candidateIds) || !claimed.compareAndSet(false, true)) {
            throw new IllegalStateException("verified mutation scope is invalid or already used");
        }
    }
}
