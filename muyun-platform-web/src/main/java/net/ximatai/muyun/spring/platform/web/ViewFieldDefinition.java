package net.ximatai.muyun.spring.platform.web;

public record ViewFieldDefinition(ViewFieldRef fieldRef,
                                  String label,
                                  UiRule<Boolean> visible,
                                  UiRule<Boolean> required,
                                  UiRule<Boolean> readOnly,
                                  String uiType,
                                  FieldValuePresentation valuePresentation,
                                  String width,
                                  Integer columnSpan,
                                  String align,
                                  Boolean fixed,
                                  BooleanStatusPresentation booleanStatus,
                                  Integer maxDisplayLines,
                                  String treeRootTitle,
                                  String overrideOf) {
    public ViewFieldDefinition {
        if (fieldRef == null) {
            throw new IllegalArgumentException("view field ref must not be null");
        }
        label = label == null || label.isBlank() ? null : label.trim();
        visible = visible == null ? UiRule.constant(Boolean.TRUE) : visible;
        required = required == null ? UiRule.constant(Boolean.FALSE) : required;
        readOnly = readOnly == null ? UiRule.constant(Boolean.FALSE) : readOnly;
        uiType = uiType == null || uiType.isBlank() ? null : uiType.trim();
        if ("fileSize".equals(uiType) || "file_size".equals(uiType)) {
            throw new IllegalArgumentException("file size must use value presentation instead of uiType");
        }
        if ("fileTransfer".equals(uiType) || "file_transfer".equals(uiType)) {
            throw new IllegalArgumentException("file transfer requires the unified file-reference lifecycle");
        }
        if (valuePresentation == FieldValuePresentation.FILE_SIZE && uiType != null) {
            throw new IllegalArgumentException("file size presentation cannot declare an input uiType");
        }
        width = width == null || width.isBlank() ? null : width.trim();
        columnSpan = columnSpan == null ? 1 : requireColumnSpan(columnSpan);
        align = align == null || align.isBlank() ? null : align.trim();
        maxDisplayLines = maxDisplayLines == null ? null : requireMaxDisplayLines(maxDisplayLines);
        treeRootTitle = treeRootTitle == null || treeRootTitle.isBlank() ? null : treeRootTitle.trim();
        overrideOf = overrideOf == null || overrideOf.isBlank() ? null : overrideOf.trim();
        if (booleanStatus != null && !"booleanStatus".equals(uiType)) {
            throw new IllegalArgumentException("boolean status presentation requires uiType booleanStatus");
        }
        if ("booleanStatus".equals(uiType) && booleanStatus == null) {
            throw new IllegalArgumentException("uiType booleanStatus requires boolean status presentation");
        }
    }

    /** Source-compatible constructor for definitions created before override semantics were introduced. */
    public ViewFieldDefinition(ViewFieldRef fieldRef,
                               String label,
                               UiRule<Boolean> visible,
                               UiRule<Boolean> required,
                               UiRule<Boolean> readOnly,
                               String uiType,
                               FieldValuePresentation valuePresentation,
                               String width,
                               Integer columnSpan,
                               String align,
                               Boolean fixed,
                               BooleanStatusPresentation booleanStatus,
                               Integer maxDisplayLines,
                               String treeRootTitle) {
        this(fieldRef, label, visible, required, readOnly, uiType, valuePresentation, width, columnSpan, align,
                fixed, booleanStatus, maxDisplayLines, treeRootTitle, null);
    }

    /** Source- and binary-compatible constructor for definitions created before value presentations were introduced. */
    public ViewFieldDefinition(ViewFieldRef fieldRef,
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
        this(fieldRef, label, visible, required, readOnly, uiType, null, width, columnSpan, align, fixed,
                booleanStatus, null, null);
    }

    public static Builder field(String fieldName) {
        return new Builder(ViewFieldRef.main(fieldName));
    }

    public static Builder field(String relationCode, String fieldName) {
        return new Builder(ViewFieldRef.relation(relationCode, fieldName));
    }

    public static final class Builder {
        private final ViewFieldRef fieldRef;
        private String label;
        private UiRule<Boolean> visible = UiRule.constant(Boolean.TRUE);
        private UiRule<Boolean> required = UiRule.constant(Boolean.FALSE);
        private UiRule<Boolean> readOnly = UiRule.constant(Boolean.FALSE);
        private String uiType;
        private FieldValuePresentation valuePresentation;
        private String width;
        private Integer columnSpan = 1;
        private String align;
        private Boolean fixed;
        private BooleanStatusPresentation booleanStatus;
        private Integer maxDisplayLines;
        private String treeRootTitle;
        private String overrideOf;

        private Builder(ViewFieldRef fieldRef) {
            this.fieldRef = fieldRef;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder required() {
            this.required = UiRule.constant(Boolean.TRUE);
            return this;
        }

        /** Makes requiredness follow the same draft-aware rule model as visibility and editability. */
        public Builder required(UiRule<Boolean> required) {
            this.required = required == null ? UiRule.constant(Boolean.FALSE) : required;
            return this;
        }

        public Builder visible(UiRule<Boolean> visible) {
            this.visible = visible == null ? UiRule.constant(Boolean.TRUE) : visible;
            return this;
        }

        public Builder hidden() {
            this.visible = UiRule.constant(Boolean.FALSE);
            return this;
        }

        public Builder readOnly() {
            this.readOnly = UiRule.constant(Boolean.TRUE);
            return this;
        }

        /**
         * Enables this editor only when the formula evaluates to true for the current draft.
         * It is presentation-only; mutation endpoints must still enforce their business invariants.
         */
        public Builder enabledWhen(UiFormula formula) {
            if (formula == null) {
                throw new IllegalArgumentException("enabled formula must not be null");
            }
            this.readOnly = UiRule.formula(formula.negated());
            return this;
        }

        public Builder disabledHint(String hint) {
            this.readOnly = new UiRule<>(readOnly.constant(), readOnly.formula(), hint);
            return this;
        }

        public Builder uiType(String uiType) {
            this.uiType = uiType;
            return this;
        }

        /** Uses the standard reference-selection control. */
        public Builder recordPicker() {
            return uiType("recordPicker");
        }

        /** Uses the standard option-selection control for an already declared field domain. */
        public Builder select() {
            return uiType("select");
        }

        /** Uses the lifecycle-aware enabled-state presentation and editor. */
        public Builder enabledStatus() {
            return uiType("enabledStatus");
        }

        /** Displays a raw byte count using the platform's standard binary-unit formatter. */
        public Builder fileSize() {
            this.valuePresentation = FieldValuePresentation.FILE_SIZE;
            return this;
        }

        /** Renders this business boolean with declared labels instead of lifecycle labels. */
        public Builder booleanStatus(String trueLabel, String falseLabel) {
            return booleanStatus(trueLabel, falseLabel, BooleanStatusTone.SUCCESS, BooleanStatusTone.NEUTRAL);
        }

        public Builder booleanStatus(String trueLabel, String falseLabel,
                                     BooleanStatusTone trueTone, BooleanStatusTone falseTone) {
            this.uiType = "booleanStatus";
            this.booleanStatus = new BooleanStatusPresentation(trueLabel, falseLabel, trueTone, falseTone);
            return this;
        }

        /** Renders a read-only collection of {@code { id, title, color }} reference summaries. */
        public Builder tagList() {
            this.uiType = "tagList";
            return this;
        }

        public Builder width(String width) {
            this.width = width;
            return this;
        }

        /** Sets the field's span in the standard two-column form and detail grid. */
        public Builder columnSpan(int columnSpan) {
            this.columnSpan = columnSpan;
            return this;
        }

        public Builder align(String align) {
            this.align = align;
            return this;
        }

        public Builder fixed() {
            this.fixed = Boolean.TRUE;
            return this;
        }

        /** Limits a text column to the given number of display lines in standard list views. */
        public Builder maxDisplayLines(int maxDisplayLines) {
            this.maxDisplayLines = requireMaxDisplayLines(maxDisplayLines);
            return this;
        }

        /**
         * Gives the standard {@code TreeAbility.ROOT_ID} sentinel a user-facing title in detail views.
         * It is declarative presentation metadata; ordinary record values are never translated implicitly.
         */
        public Builder treeRootTitle(String treeRootTitle) {
            this.treeRootTitle = treeRootTitle;
            return this;
        }

        /**
         * Declares this nullable field as a governed override of one source field.
         * A null value means inherit; standard forms therefore render an explicit
         * inherit/override choice instead of an ordinary nullable editor.
         */
        public Builder overrideOf(ModuleUiField sourceField) {
            if (sourceField == null) {
                throw new IllegalArgumentException("override source field must not be null");
            }
            return overrideOf(sourceField.name());
        }

        public Builder overrideOf(String sourceFieldName) {
            if (sourceFieldName == null || sourceFieldName.isBlank()) {
                throw new IllegalArgumentException("override source field must not be blank");
            }
            this.overrideOf = sourceFieldName;
            return this;
        }

        public ViewFieldDefinition build() {
            return new ViewFieldDefinition(fieldRef, label, visible, required, readOnly,
                    uiType, valuePresentation, width, columnSpan, align, fixed, booleanStatus, maxDisplayLines,
                    treeRootTitle, overrideOf);
        }
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
