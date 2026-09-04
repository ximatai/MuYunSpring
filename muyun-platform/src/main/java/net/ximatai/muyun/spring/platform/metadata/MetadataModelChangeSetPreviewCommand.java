package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

/** One draft for the whole visible metadata model tree of a dynamic module. */
public record MetadataModelChangeSetPreviewCommand(
        List<MetadataModelRelationChangeSetDraft> relationDrafts,
        List<MetadataModelRelationOrder> relationOrders,
        List<MetadataModelFieldOrder> fieldOrders
) {
}
