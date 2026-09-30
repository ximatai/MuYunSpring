package net.ximatai.muyun.spring.common.model.constraint;

/** Model requirements projected onto user-editable input, evaluated against the editor operation. */
public record FieldInputRequirements(boolean requiredOnInsert, boolean requiredOnUpdate, Integer maxLength, Integer precision, Integer scale, String validationRegex, TextNormalization textNormalization) {
    public FieldInputRequirements(boolean requiredOnInsert, boolean requiredOnUpdate, Integer maxLength, Integer precision, Integer scale, String validationRegex) {
        this(requiredOnInsert, requiredOnUpdate, maxLength, precision, scale, validationRegex, TextNormalization.NONE);
    }
    public FieldInputRequirements(boolean requiredOnInsert, boolean requiredOnUpdate, Integer maxLength, Integer precision, Integer scale) {
        this(requiredOnInsert, requiredOnUpdate, maxLength, precision, scale, null);
    }
    public FieldInputRequirements(boolean requiredOnInsert, boolean requiredOnUpdate) {
        this(requiredOnInsert, requiredOnUpdate, null, null, null);
    }

    public static final FieldInputRequirements NONE = new FieldInputRequirements(false, false);
}
