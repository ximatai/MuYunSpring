package net.ximatai.muyun.spring.platform.ui;

/**
 * Server-resolved, display-safe field metadata for a relation list.  It intentionally does not
 * expose the mutable UI-config model or ask a Web runner to interpret raw layout JSON.
 */
public record ResolvedDetailRelationListField(
        String fieldName,
        String title,
        String fieldForm,
        String fieldUiControlAlias,
        String valueType,
        Integer width,
        String align,
        Integer maxDisplayLines,
        String referenceTitleField
) {
    public ResolvedDetailRelationListField {
        referenceTitleField = referenceTitleField == null || referenceTitleField.isBlank()
                ? null : referenceTitleField.trim();
    }
    /**
     * Compatibility constructor for relation projections issued before value-type facts were
     * added.  Consumers must retain their existing text fallback for such descriptors.
     */
    public ResolvedDetailRelationListField(String fieldName,
                                           String title,
                                           String fieldForm,
                                           String fieldUiControlAlias,
                                           Integer width,
                                           String align,
                                           Integer maxDisplayLines) {
        this(fieldName, title, fieldForm, fieldUiControlAlias, null, width, align, maxDisplayLines, null);
    }

    /** Compatibility constructor for descriptors issued before reference read-side facts were added. */
    public ResolvedDetailRelationListField(String fieldName, String title, String fieldForm,
                                           String fieldUiControlAlias, String valueType, Integer width,
                                           String align, Integer maxDisplayLines) {
        this(fieldName, title, fieldForm, fieldUiControlAlias, valueType, width, align, maxDisplayLines, null);
    }
}
