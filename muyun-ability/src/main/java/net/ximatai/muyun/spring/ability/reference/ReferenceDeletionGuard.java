package net.ximatai.muyun.spring.ability.reference;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.ErrorTarget;

import java.util.List;
import java.util.Map;
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

    static PlatformException referencedTarget(ReferenceTarget target, String targetId,
                                              String sourceModuleAlias, String sourceField, long referenceCount) {
        return new PlatformException(PlatformErrorCodes.RESOURCE_IN_USE, 409,
                "该记录仍被其他记录引用，不能删除", ErrorScope.empty(), List.of(ErrorTarget.record(targetId)),
                Map.of("referenceTarget", target.qualifiedName(), "sourceModuleAlias", sourceModuleAlias,
                        "sourceField", sourceField, "referenceCount", referenceCount));
    }

}
