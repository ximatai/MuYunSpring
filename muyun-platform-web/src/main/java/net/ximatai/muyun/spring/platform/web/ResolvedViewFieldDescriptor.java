package net.ximatai.muyun.spring.platform.web;

public record ResolvedViewFieldDescriptor(ViewFieldRef fieldRef,
                                          String label,
                                          UiRule<Boolean> visible,
                                          UiRule<Boolean> required,
                                          UiRule<Boolean> readOnly,
                                          String uiType,
                                          FieldValueType valueType,
                                          FieldValuePresentation valuePresentation,
                                          String width,
                                          Integer columnSpan,
                                          String align,
                                          Boolean fixed,
                                          BooleanStatusPresentation booleanStatus,
                                          ResolvedOptionFieldDescriptor option,
                                          ResolvedReferenceFieldDescriptor reference,
                                          ResolvedReferenceSummaryFieldDescriptor referenceSummary,
                                          Integer maxDisplayLines) {
    public ResolvedViewFieldDescriptor {
        if (fieldRef == null) {
            throw new IllegalArgumentException("resolved view field ref must not be null");
        }
        label = label == null || label.isBlank() ? null : label.trim();
        visible = visible == null ? UiRule.constant(Boolean.TRUE) : visible;
        required = required == null ? UiRule.constant(Boolean.FALSE) : required;
        readOnly = readOnly == null ? UiRule.constant(Boolean.FALSE) : readOnly;
        uiType = uiType == null || uiType.isBlank() ? null : uiType.trim();
        if (valuePresentation == FieldValuePresentation.FILE_SIZE && uiType != null) {
            throw new IllegalArgumentException("file size presentation cannot declare an input uiType");
        }
        width = width == null || width.isBlank() ? null : width.trim();
        columnSpan = columnSpan == null ? 1 : requireColumnSpan(columnSpan);
        align = align == null || align.isBlank() ? null : align.trim();
        maxDisplayLines = maxDisplayLines == null ? null : requireMaxDisplayLines(maxDisplayLines);
        if (booleanStatus != null && !"booleanStatus".equals(uiType)) {
            throw new IllegalArgumentException("boolean status presentation requires uiType booleanStatus");
        }
        if ("booleanStatus".equals(uiType) && booleanStatus == null) {
            throw new IllegalArgumentException("uiType booleanStatus requires boolean status presentation");
        }
    }

    /** Source- and binary-compatible constructor for descriptors created before value presentations were introduced. */
    public ResolvedViewFieldDescriptor(ViewFieldRef fieldRef,
                                       String label,
                                       UiRule<Boolean> visible,
                                       UiRule<Boolean> required,
                                       UiRule<Boolean> readOnly,
                                       String uiType,
                                       FieldValueType valueType,
                                       String width,
                                       Integer columnSpan,
                                       String align,
                                       Boolean fixed,
                                       BooleanStatusPresentation booleanStatus,
                                       ResolvedOptionFieldDescriptor option,
                                       ResolvedReferenceFieldDescriptor reference,
                                       ResolvedReferenceSummaryFieldDescriptor referenceSummary) {
        this(fieldRef, label, visible, required, readOnly, uiType, valueType, null, width, columnSpan, align, fixed,
                booleanStatus, option, reference, referenceSummary, null);
    }

    public ResolvedViewFieldDescriptor(ViewFieldRef fieldRef,
                                       String label,
                                       UiRule<Boolean> visible,
                                       UiRule<Boolean> required,
                                       UiRule<Boolean> readOnly,
                                       String uiType,
                                       String width,
                                       Integer columnSpan,
                                       String align,
                                       Boolean fixed) {
        this(fieldRef, label, visible, required, readOnly, uiType, null, null, width, columnSpan, align, fixed,
                null, null, null, null, null);
    }

    /** Source-compatible constructor for descriptors with option metadata only. */
    public ResolvedViewFieldDescriptor(ViewFieldRef fieldRef,
                                       String label,
                                       UiRule<Boolean> visible,
                                       UiRule<Boolean> required,
                                       UiRule<Boolean> readOnly,
                                       String uiType,
                                       String width,
                                       Integer columnSpan,
                                       String align,
                                       Boolean fixed,
                                       ResolvedOptionFieldDescriptor option) {
        this(fieldRef, label, visible, required, readOnly, uiType, null, null, width, columnSpan, align, fixed,
                null, option, null, null, null);
    }

    /** Source-compatible constructor for descriptors created before column spans were introduced. */
    public ResolvedViewFieldDescriptor(ViewFieldRef fieldRef,
                                       String label,
                                       UiRule<Boolean> visible,
                                       UiRule<Boolean> required,
                                       UiRule<Boolean> readOnly,
                                       String uiType,
                                       String width,
                                       String align,
                                       Boolean fixed) {
        this(fieldRef, label, visible, required, readOnly, uiType, null, null, width, 1, align, fixed,
                null, null, null, null, null);
    }

    /** Source-compatible constructor with a boolean status presentation. */
    public ResolvedViewFieldDescriptor(ViewFieldRef fieldRef,
                                       String label,
                                       UiRule<Boolean> visible,
                                       UiRule<Boolean> required,
                                       UiRule<Boolean> readOnly,
                                       String uiType,
                                       String width,
                                       Integer columnSpan,
                                       String align,
                                       Boolean fixed,
                                       BooleanStatusPresentation booleanStatus) {
        this(fieldRef, label, visible, required, readOnly, uiType, null, null, width, columnSpan, align, fixed,
                booleanStatus, null, null, null, null);
    }

    private static int requireColumnSpan(int value) {
        if (value < 1 || value > 2) {
            throw new IllegalArgumentException("columnSpan must be between 1 and 2");
        }
        return value;
    }

    private static int requireMaxDisplayLines(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("maxDisplayLines must be at least 1");
        }
        return value;
    }
}
