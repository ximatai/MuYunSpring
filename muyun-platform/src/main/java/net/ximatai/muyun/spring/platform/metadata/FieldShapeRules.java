package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.common.exception.DeclarationValidationException;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.Map;

final class FieldShapeRules {
    private FieldShapeRules() {
    }

    static void validate(FieldType type, Integer length, Integer precision, Integer scale, String context) {
        if (length != null && length <= 0) {
            throw new DeclarationValidationException(TextReference.code(
                    "platform.declaration.field-shape.length-positive", "field length must be positive: {context}"),
                    Map.of("context", String.valueOf(context)));
        }
        if (length != null && type != FieldType.STRING && type != FieldType.TEXT) {
            throw new DeclarationValidationException(TextReference.code(
                    "platform.declaration.field-shape.length-string", "field length only applies to string fields: {context}"),
                    Map.of("context", String.valueOf(context)));
        }
        if (precision != null && precision <= 0) {
            throw new DeclarationValidationException(TextReference.code(
                    "platform.declaration.field-shape.precision-positive", "field precision must be positive: {context}"),
                    Map.of("context", String.valueOf(context)));
        }
        if (scale != null && scale < 0) {
            throw new DeclarationValidationException(TextReference.code(
                    "platform.declaration.field-shape.scale-nonnegative", "field scale must not be negative: {context}"),
                    Map.of("context", String.valueOf(context)));
        }
        if ((precision != null || scale != null) && type != FieldType.DECIMAL) {
            throw new DeclarationValidationException(TextReference.code(
                    "platform.declaration.field-shape.precision-decimal", "field precision and scale only apply to decimal fields: {context}"),
                    Map.of("context", String.valueOf(context)));
        }
        if (scale != null && precision == null) {
            throw new DeclarationValidationException(TextReference.code(
                    "platform.declaration.field-shape.scale-precision-required", "field scale requires precision: {context}"),
                    Map.of("context", String.valueOf(context)));
        }
        if (scale != null && scale > precision) {
            throw new DeclarationValidationException(TextReference.code(
                    "platform.declaration.field-shape.scale-within-precision", "field scale must not exceed precision: {context}"),
                    Map.of("context", String.valueOf(context)));
        }
    }
}
