package net.ximatai.muyun.spring.platform.metadata;

/**
 * One relation-scoped business property proposal for a metadata field.  Exactly the binding
 * associated with {@link #kind()} may be supplied; BASIC clears neither historical data nor
 * physical field structure.
 */
public record MetadataFieldPropertyDraft(
        MetadataFieldPropertyKind kind,
        Integer expectedBindingVersion,
        MetadataFieldReferenceConfigDraft referenceConfig,
        MetadataFieldConfig dictionaryConfig,
        MetadataFieldFixedDefaultDraft fixedDefault
) {
    public MetadataFieldPropertyDraft(MetadataFieldPropertyKind kind, Integer expectedBindingVersion,
                                      MetadataFieldReferenceConfigDraft referenceConfig, MetadataFieldConfig dictionaryConfig) {
        this(kind, expectedBindingVersion, referenceConfig, dictionaryConfig, null);
    }
    public MetadataFieldPropertyDraft(MetadataFieldPropertyKind kind) {
        this(kind, null, (MetadataFieldReferenceConfigDraft) null, null);
    }

}
