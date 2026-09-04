package net.ximatai.muyun.spring.platform.metadata;

/** Input for creating a child entity beneath one module metadata node. */
public record ModuleChildMetadataCreateCommand(
        String alias,
        String title,
        String schemaName,
        String tableName) {
}
