package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.List;
import java.util.Set;

/** Immutable, normalized write plan produced by preview and consumed by apply. */
public record MetadataRelationChangeSetPlan(
        String metadataId,
        Integer expectedMetadataVersion,
        Set<EntityCapability> effectiveCapabilities,
        boolean replaceCapabilityDeclarations,
        List<MetadataFieldChangeSetPlan> fieldMutations
) {
}
