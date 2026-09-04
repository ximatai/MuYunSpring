package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

/** Version-bound relation ordering plan for one sibling group. */
public record MetadataModelRelationOrderPlan(String parentMetadataId, List<Entry> entries) {
    public record Entry(String relationId, Integer expectedVersion, int sortOrder) {
    }
}
