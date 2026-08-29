package net.ximatai.muyun.spring.platform.metadata;

/** A field-level effect calculated from the final proposed metadata model. */
public record MetadataChangeSetFieldImpact(String operation, String fieldName, String columnName,
                                           boolean platformManaged, String description) {
}
