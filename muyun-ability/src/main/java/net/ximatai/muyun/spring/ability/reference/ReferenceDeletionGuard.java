package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.deletion.DeletionContext;
import net.ximatai.muyun.spring.ability.deletion.DeletionMode;
import net.ximatai.muyun.spring.ability.deletion.DeletionNode;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;

/**
 * Platform hook that enforces inbound reference policy before a target becomes
 * unavailable through a standard soft or hard delete.
 */
@FunctionalInterface
public interface ReferenceDeletionGuard {
    ReferenceDeletionGuard NONE = (ability, entity) -> { };

    void validateTargetUnavailable(CrudAbility<?> ability, EntityContract entity);

    default void beforeTargetUnavailable(CrudAbility<?> ability, EntityContract entity) {
        validateTargetUnavailable(ability, entity);
    }

    default void cascadeTargetUnavailable(CrudAbility<?> ability,
                                          EntityContract entity,
                                          DeletionContext context,
                                          DeletionNode node,
                                          DeletionMode mode) {
    }

    default void beforeTargetUnavailable(CrudAbility<?> ability,
                                         EntityContract entity,
                                         DeletionContext context,
                                         DeletionNode node,
                                         DeletionMode mode) {
        validateTargetUnavailable(ability, entity);
        cascadeTargetUnavailable(ability, entity, context, node, mode);
    }
}
