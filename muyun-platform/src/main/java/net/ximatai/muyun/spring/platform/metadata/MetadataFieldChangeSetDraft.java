package net.ximatai.muyun.spring.platform.metadata;

/** One staged metadata-field operation inside a relation-scoped edit session. */
public record MetadataFieldChangeSetDraft(
        Operation operation,
        String fieldId,
        Integer expectedFieldVersion,
        MetadataField field,
        MetadataFieldPropertyDraft property
) {
    public MetadataFieldChangeSetDraft(Operation operation, String fieldId, Integer expectedFieldVersion, MetadataField field) {
        this(operation, fieldId, expectedFieldVersion, field, null);
    }

    /** Kept for source compatibility; UPDATE now requires an explicit field version at validation time. */
    public MetadataFieldChangeSetDraft(Operation operation, String fieldId, MetadataField field) {
        this(operation, fieldId, null, field, null);
    }

    public enum Operation { ADD, UPDATE, DELETE }
}
