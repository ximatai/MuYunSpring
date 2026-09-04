package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.Set;

/** Normalized plan for one relation contained in a module model publish. */
public record MetadataModelRelationPlan(
        String relationId,
        String moduleAlias,
        Integer expectedRelationVersion,
        String metadataId,
        RelationRole relationRole,
        String parentMetadataId,
        String foreignKey,
        MetadataRelationChangeSetPlan changeSet,
        Set<EntityCapability> effectiveCapabilities
) {
}
