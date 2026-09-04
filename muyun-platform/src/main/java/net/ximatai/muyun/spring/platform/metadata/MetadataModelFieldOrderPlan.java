package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

/** Version-bound field ordering plan and immutable relation scope for one metadata relation. */
public record MetadataModelFieldOrderPlan(
        String relationId,
        String moduleAlias,
        Integer expectedRelationVersion,
        String metadataId,
        RelationRole relationRole,
        String parentMetadataId,
        String foreignKey,
        List<Entry> entries
) {
    public record Entry(String fieldId, Integer expectedVersion, int sortOrder) {
    }
}
