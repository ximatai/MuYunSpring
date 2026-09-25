package net.ximatai.muyun.spring.common.model.constraint;

/** Explicit text processing; required fields do not implicitly trim their values. */
public enum TextNormalization {
    NONE, TRIM, TRIM_TO_NULL;

    public Object normalize(Object value) {
        if (this == NONE || value == null) return value;
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("text normalization requires a string value");
        }
        String trimmed = text.trim();
        return this == TRIM_TO_NULL && trimmed.isBlank() ? null : trimmed;
    }
}
