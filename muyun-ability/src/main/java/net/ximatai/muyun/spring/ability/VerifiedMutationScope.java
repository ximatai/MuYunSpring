package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformAction;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opaque proof that a concrete service mutation was checked against an effective data policy.
 * Only ability-level scope resolution can issue this one-shot proof.
 */
public final class VerifiedMutationScope {
    private final CrudAbility<?> service;
    private final PlatformAction action;
    private final Set<String> recordIds;
    private final DataScopeCriteriaResult criteriaResult;
    private final AtomicBoolean claimed = new AtomicBoolean();

    VerifiedMutationScope(CrudAbility<?> service, PlatformAction action, Set<String> recordIds,
                          DataScopeCriteriaResult criteriaResult) {
        this.service = java.util.Objects.requireNonNull(service, "service must not be null");
        this.action = java.util.Objects.requireNonNull(action, "action must not be null");
        this.recordIds = Set.copyOf(recordIds);
        this.criteriaResult = java.util.Objects.requireNonNull(criteriaResult,
                "criteriaResult must not be null");
    }

    DataScopeCriteriaResult criteriaResult() {
        return criteriaResult;
    }

    boolean matches(CrudAbility<?> candidate, PlatformAction candidateAction, Set<String> candidateIds) {
        return service == candidate && action == candidateAction && recordIds.equals(candidateIds);
    }

    boolean belongsTo(CrudAbility<?> candidate) {
        return service == candidate;
    }

    void claim(CrudAbility<?> candidate, PlatformAction candidateAction, Set<String> candidateIds) {
        if (!matches(candidate, candidateAction, candidateIds) || !claimed.compareAndSet(false, true)) {
            throw new IllegalStateException("verified mutation scope is invalid or already used");
        }
    }
}
