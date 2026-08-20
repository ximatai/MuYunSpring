package net.ximatai.muyun.spring.platform.web;

/** A named component of a composite field-control value. */
public record ResolvedFieldControlBindingDescriptor(String key, String valueType) {
    public ResolvedFieldControlBindingDescriptor {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("field control binding key must not be blank");
        }
        if (valueType == null || valueType.isBlank()) {
            throw new IllegalArgumentException("field control binding value type must not be blank");
        }
        key = key.trim();
        valueType = valueType.trim();
    }
}
