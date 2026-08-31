package net.ximatai.muyun.spring.platform.metadata;

/** Business data property of a metadata field, independent from its physical field spec. */
public enum MetadataFieldPropertyKind {
    BASIC,
    MODULE_REFERENCE,
    DICTIONARY,
    /** Legacy ModuleMetadataField binding; editing is blocked until explicitly migrated. */
    LEGACY_LOCKED
}
