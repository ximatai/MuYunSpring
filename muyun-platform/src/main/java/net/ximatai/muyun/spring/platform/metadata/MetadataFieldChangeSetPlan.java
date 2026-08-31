package net.ximatai.muyun.spring.platform.metadata;

/** A normalized field mutation that is safe for the atomic publisher to consume. */
public record MetadataFieldChangeSetPlan(
        MetadataFieldChangeSetDraft.Operation operation,
        String fieldId,
        Integer expectedFieldVersion,
        MetadataField field,
        MetadataFieldPropertyChangeSetPlan property
) {
    public MetadataFieldChangeSetPlan(MetadataFieldChangeSetDraft.Operation operation,
                                      String fieldId,
                                      Integer expectedFieldVersion,
                                      MetadataField field) {
        this(operation, fieldId, expectedFieldVersion, field, null);
    }
}
