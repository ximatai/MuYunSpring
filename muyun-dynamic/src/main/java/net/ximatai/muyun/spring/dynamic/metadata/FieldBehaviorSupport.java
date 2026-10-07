package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.model.constraint.TextNormalization;

import java.math.BigDecimal;
import java.util.List;

public final class FieldBehaviorSupport {
    private FieldBehaviorSupport() {
    }

    public static Object parseDefaultValue(FieldType type, String value) {
        return DynamicFieldValueSupport.parseDefaultValue(type, value);
    }

    /** Validate the normalized initial value against the same physical shape used by storage. */
    public static void validateBehavior(FieldDefinition field) {
        validateBehavior(field.type(), field.behavior(), field.code());
        Object value = field.behavior().writeRules().normalize(
                parseDefaultValue(field.type(), field.behavior().defaultValue()));
        if (field.behavior().defaultValue() != null) {
            field.resolvedWriteRules().validate(field.code(), value, false);
        }
        if (value != null && field.valueShape() == FieldValueShape.JSON_SET
                && (!(value instanceof List<?> items) || items.stream().anyMatch(item -> !(item instanceof String)))) {
            throw new IllegalArgumentException("JSON_SET defaultValue requires a string collection: " + field.code());
        }
        if (value instanceof String text && field.length() != null
                && text.codePointCount(0, text.length()) > field.length()) {
            throw new IllegalArgumentException("defaultValue exceeds field length: " + field.code());
        }
        if (value instanceof BigDecimal decimal) {
            BigDecimal significant = decimal.stripTrailingZeros();
            long fractionalDigits = Math.max(0L, significant.scale());
            long integerDigits = significant.signum() == 0 ? 0
                    : Math.max(0L, (long) significant.precision() - significant.scale());
            if (field.scale() != null && fractionalDigits > field.scale()) {
                throw new IllegalArgumentException("defaultValue exceeds field scale: " + field.code());
            }
            if (field.precision() != null && (field.scale() == null
                    ? integerDigits + fractionalDigits > field.precision()
                    : integerDigits > field.precision() - field.scale())) {
                throw new IllegalArgumentException("defaultValue exceeds field precision: " + field.code());
            }
        }
    }

    public static void validateBehavior(FieldType type, FieldBehaviorDefinition behavior, String fieldCode) {
        if (behavior.writeRules().textNormalization() != TextNormalization.NONE
                && type != FieldType.STRING && type != FieldType.TEXT) {
            throw new IllegalArgumentException("text normalization requires string field: " + fieldCode);
        }
        if (behavior.validationRegex() != null) {
            if (type != FieldType.STRING && type != FieldType.TEXT) {
                throw new IllegalArgumentException("validationRegex requires string field: " + fieldCode);
            }
            java.util.regex.Pattern.compile(behavior.validationRegex());
        }
        if (behavior.defaultValue() != null) {
            Object parsed = behavior.writeRules().normalize(parseDefaultValue(type, behavior.defaultValue()));
            if (behavior.validationRegex() != null
                    && parsed instanceof String text
                    && !text.matches(behavior.validationRegex())) {
                throw new IllegalArgumentException("defaultValue does not match validationRegex: " + fieldCode);
            }
        }
    }

}
