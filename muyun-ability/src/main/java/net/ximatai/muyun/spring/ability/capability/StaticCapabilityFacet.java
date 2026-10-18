package net.ximatai.muyun.spring.ability.capability;

import net.ximatai.muyun.spring.ability.PlatformOperationDefinition;

import java.util.List;

/**
 * Source-neutral static-service contribution of a platform capability.
 *
 * <p>The platform compiler only understands this small contract.  A capability keeps the
 * knowledge of its service marker and operation facts beside its own implementation instead of
 * adding another branch to the static compiler.</p>
 */
public interface StaticCapabilityFacet {
    boolean supports(Object service);

    /** Actions may be hosted by a shared domain HTTP bridge rather than a service operation. */
    default List<net.ximatai.muyun.spring.common.platform.PlatformAction> standardActions(StaticCapabilityOperationContext context) {
        return standardOperations(context).stream().map(PlatformOperationDefinition::action).distinct().toList();
    }

    default List<PlatformOperationDefinition> standardOperations(StaticCapabilityOperationContext context) {
        return List.of();
    }
}
