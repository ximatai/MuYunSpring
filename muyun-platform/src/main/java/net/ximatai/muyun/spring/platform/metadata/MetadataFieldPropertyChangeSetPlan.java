package net.ximatai.muyun.spring.platform.metadata;

/** A normalized relation-scoped field property mutation consumed by the atomic publisher. */
public record MetadataFieldPropertyChangeSetPlan(
        MetadataFieldPropertyKind kind,
        Integer expectedBindingVersion,
        MetadataFieldReferenceConfig referenceConfig,
        MetadataFieldConfig dictionaryConfig
) {
}
