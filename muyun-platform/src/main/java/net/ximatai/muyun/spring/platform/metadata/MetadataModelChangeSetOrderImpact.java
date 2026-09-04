package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

/** User-visible reorder impact inside one metadata model proposal. */
public record MetadataModelChangeSetOrderImpact(
        String operation,
        String relationId,
        String parentMetadataId,
        List<String> orderedIds,
        String description
) {
}
