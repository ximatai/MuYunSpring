package net.ximatai.muyun.spring.platform.metadata;

/** A physical schema effect calculated without executing DDL. */
public record MetadataChangeSetSchemaImpact(String operation, String schemaName, String tableName,
                                            String columnName, String description) {
}
