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

    default List<PlatformOperationDefinition> standardOperations(StaticCapabilityOperationContext context) {
        return List.of();
    }
}
