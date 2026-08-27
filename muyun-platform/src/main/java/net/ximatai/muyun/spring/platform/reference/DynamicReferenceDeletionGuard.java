package net.ximatai.muyun.spring.platform.reference;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.deletion.DeletionContext;
import net.ximatai.muyun.spring.ability.deletion.DeletionMode;
import net.ximatai.muyun.spring.ability.deletion.DeletionNode;
import net.ximatai.muyun.spring.ability.reference.ReferenceDeletionGuard;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargets;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;

/** Checks dynamic referrers before any static or dynamic target is soft-deleted. */
public final class DynamicReferenceDeletionGuard implements ReferenceDeletionGuard {
    private final DynamicRecordRuntime runtime;

    public DynamicReferenceDeletionGuard(DynamicRecordRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void validateTargetUnavailable(CrudAbility<?> targetAbility, EntityContract target) {
        if (targetAbility == null || target == null || target.getId() == null || target.getId().isBlank()) {
            return;
        }
        runtime.validateReferenceTargetDeletion(targetOf(targetAbility), target.getId());
    }

    @Override
    public void cascadeTargetUnavailable(CrudAbility<?> targetAbility,
                                         EntityContract target,
                                         DeletionContext context,
                                         DeletionNode node,
                                         DeletionMode mode) {
        if (targetAbility == null || target == null || target.getId() == null || target.getId().isBlank()) {
            return;
        }
        runtime.cascadeReferenceTargetUnavailable(targetOf(targetAbility), target.getId(), context, node);
    }

    private ReferenceTarget targetOf(CrudAbility<?> ability) {
        return ReferenceTargets.of(ability);
    }
}
