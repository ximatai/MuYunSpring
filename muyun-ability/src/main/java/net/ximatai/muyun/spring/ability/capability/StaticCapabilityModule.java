package net.ximatai.muyun.spring.ability.capability;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.Optional;
import java.util.Set;

/**
 * Source-neutral capability facts needed when a Java service is compiled into a module plan.
 *
 * <p>Dynamic metadata validation and runtime HTTP handling intentionally do not appear here.
 * They are adapters owned by the dynamic runtime, while static compilation only needs the
 * capability identity, its prerequisites and its static-service facet.</p>
 */
public interface StaticCapabilityModule {
    EntityCapability capability();

    default Set<EntityCapability> dependencies() {
        return Set.of();
    }

    default Optional<StaticCapabilityFacet> staticFacet() {
        return Optional.empty();
    }
}
