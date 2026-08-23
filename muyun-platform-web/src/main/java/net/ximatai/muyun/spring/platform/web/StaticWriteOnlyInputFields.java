package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves the small, existing model convention for a standard-form input that
 * is accepted on writes but never becomes a persisted or readable model fact.
 */
final class StaticWriteOnlyInputFields {
    private StaticWriteOnlyInputFields() {
    }

    static Map<String, FieldValueType> resolve(Class<?> modelClass) {
        if (modelClass == null || modelClass == Object.class) {
            return Map.of();
        }
        LinkedHashMap<String, FieldValueType> fields = new LinkedHashMap<>();
        for (Class<?> current = modelClass; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                JsonProperty property = field.getAnnotation(JsonProperty.class);
                if (!Modifier.isTransient(field.getModifiers())
                        || property == null
                        || property.access() != JsonProperty.Access.WRITE_ONLY) {
                    continue;
                }
                fields.putIfAbsent(field.getName(), valueType(field.getType()));
            }
        }
        return Map.copyOf(fields);
    }

    private static FieldValueType valueType(Class<?> type) {
        if (type == Boolean.class || type == boolean.class) return FieldValueType.BOOLEAN;
        if (type == Integer.class || type == int.class) return FieldValueType.INTEGER;
        if (type == Long.class || type == long.class) return FieldValueType.LONG;
        if (type == java.math.BigDecimal.class) return FieldValueType.DECIMAL;
        if (type == LocalDate.class) return FieldValueType.DATE;
        if (type == Instant.class) return FieldValueType.TIMESTAMP;
        return FieldValueType.STRING;
    }
}
