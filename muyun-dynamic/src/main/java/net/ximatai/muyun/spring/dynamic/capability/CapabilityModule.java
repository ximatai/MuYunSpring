package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.Optional;
import java.util.Set;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityFacet;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityModule;

/**
 * Platform-owned capability composition contract.  Registries remain explicitly assembled by the
 * platform; the interface is intentionally extensible so compiler tests and future first-party
 * capabilities do not require central branching.
 */
public interface CapabilityModule extends StaticCapabilityModule {
    @Override
    EntityCapability capability();

    default Set<EntityCapability> dependencies() {
        return Set.of();
    }

    /** Dynamic metadata is an adapter facet, not part of the source-neutral capability contract. */
    default Optional<DynamicCapabilityDefinitionFacet> dynamicDefinitionFacet() {
        return Optional.empty();
    }

    /** Static service detection and operation facts, when this capability supports static modules. */
    @Override
    default Optional<StaticCapabilityFacet> staticFacet() { return Optional.empty(); }

    CapabilityActionContribution actionContribution();
}
