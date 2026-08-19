package net.ximatai.muyun.spring.dynamic.capability;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.Set;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;

/** Closed capability-module vocabulary; this is not a third-party extension SPI. */
public sealed interface CapabilityModule permits EnableCapabilityModule {
    EntityCapability capability();

    default Set<EntityCapability> dependencies() {
        return Set.of();
    }

    default void validateDynamicDefinition(EntityDefinition entity) {
    }

    CapabilityActionContribution actionContribution();
}
