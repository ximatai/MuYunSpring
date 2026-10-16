package net.ximatai.muyun.spring.common.model.constraint;

/** A stable input-constraint fact; the original diagnostic remains available to non-web callers. */
public final class FieldInputViolationException extends IllegalArgumentException {
    public enum Constraint { NON_NULL, REQUIRED }
    private final String fieldName;
    private final Constraint constraint;

    public FieldInputViolationException(String fieldName, Constraint constraint) {
        super(fieldName + (constraint == Constraint.NON_NULL ? " must not be null" : " must not be blank"));
        this.fieldName = fieldName;
        this.constraint = constraint;
    }
    public String fieldName() { return fieldName; }
    public Constraint constraint() { return constraint; }
}
