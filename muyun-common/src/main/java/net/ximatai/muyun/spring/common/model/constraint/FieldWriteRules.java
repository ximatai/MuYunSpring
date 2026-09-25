package net.ximatai.muyun.spring.common.model.constraint;

/** Source-independent save rules. nonNull projects an existing storage contract; it never changes DDL. */
public record FieldWriteRules(boolean requiredOnInsert, boolean requiredOnUpdate,
                              TextNormalization textNormalization, boolean nonNull) {
    public static final FieldWriteRules NONE = new FieldWriteRules(false, false, TextNormalization.NONE);

    public FieldWriteRules(boolean requiredOnInsert, boolean requiredOnUpdate, TextNormalization textNormalization) {
        this(requiredOnInsert, requiredOnUpdate, textNormalization, false);
    }

    public FieldWriteRules {
        textNormalization = textNormalization == null ? TextNormalization.NONE : textNormalization;
    }

    public FieldWriteRules withNonNull(boolean value) {
        return new FieldWriteRules(requiredOnInsert, requiredOnUpdate, textNormalization, value);
    }

    public boolean required(boolean update) {
        return update ? requiredOnUpdate : requiredOnInsert;
    }

    public Object normalize(Object value) {
        return textNormalization.normalize(value);
    }

    public void validate(String fieldName, Object value, boolean update) {
        if (nonNull && value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        if (required(update) && (value == null || value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
