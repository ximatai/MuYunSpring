package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

/** Complete ordered sibling relation ids under one parent metadata id (null denotes root). */
public record MetadataModelRelationOrder(String parentMetadataId, List<String> relationIds) {
}
