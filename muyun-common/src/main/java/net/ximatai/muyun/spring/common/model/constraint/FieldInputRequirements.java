package net.ximatai.muyun.spring.common.model.constraint;

/** Model requirements projected onto user-editable input, evaluated against the editor operation. */
public record FieldInputRequirements(boolean requiredOnInsert, boolean requiredOnUpdate) {
    public static final FieldInputRequirements NONE = new FieldInputRequirements(false, false);
}
