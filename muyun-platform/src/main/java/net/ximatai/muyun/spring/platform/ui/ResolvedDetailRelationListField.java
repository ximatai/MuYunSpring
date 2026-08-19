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
        Integer width,
        String align,
        Integer maxDisplayLines
) {
}
