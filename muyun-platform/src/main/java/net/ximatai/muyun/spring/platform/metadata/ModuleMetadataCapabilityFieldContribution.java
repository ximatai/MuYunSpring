package net.ximatai.muyun.spring.platform.metadata;

/** A field managed by the platform when a capability becomes active. */
public record ModuleMetadataCapabilityFieldContribution(
        String fieldName,
        String columnName,
        String fieldSpecAlias,
        String defaultKind,
        String defaultDescription
) {
}
