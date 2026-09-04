package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

/** Complete ordered movable field ids of one existing relation. */
public record MetadataModelFieldOrder(String relationId, List<String> fieldIds) {
}
