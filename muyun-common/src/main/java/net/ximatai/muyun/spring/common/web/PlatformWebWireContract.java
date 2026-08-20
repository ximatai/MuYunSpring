package net.ximatai.muyun.spring.common.web;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Source-neutral HTTP representation for platform field values.
 *
 * <p>The browser cannot faithfully represent int64 and arbitrary decimal values as a JavaScript
 * number. Standard module adapters therefore expose those values as canonical text at their HTTP
 * boundary. The domain model remains strongly typed; this contract only describes the wire.</p>
 */
public final class PlatformWebWireContract {
    private PlatformWebWireContract() {
    }

    public static WireShape openApiShape(String fieldValueType) {
        return switch (normalize(fieldValueType)) {
            case "STRING", "TEXT" -> new WireShape("string", null);
            case "INTEGER" -> new WireShape("integer", "int32");
            case "LONG" -> new WireShape("string", "int64");
            case "BOOLEAN" -> new WireShape("boolean", null);
            case "DATE" -> new WireShape("string", "date");
            case "TIMESTAMP", "ZONED_TIMESTAMP" -> new WireShape("string", "date-time");
            case "DECIMAL" -> new WireShape("string", "decimal");
            case "JSON" -> new WireShape("object", null);
            default -> new WireShape("string", null);
        };
    }

    /** Converts only values whose JSON numeric representation is lossy in JavaScript. */
    public static Object responseValue(String fieldValueType, Object value) {
        if (value == null) {
            return null;
        }
        return switch (normalize(fieldValueType)) {
            case "LONG" -> value instanceof Number number ? number.toString() : value;
            case "DECIMAL" -> value instanceof BigDecimal decimal ? decimal.toPlainString()
                    : value instanceof Number number ? number.toString() : value;
            default -> value;
        };
    }

    private static String normalize(String fieldValueType) {
        return fieldValueType == null ? "" : fieldValueType.trim().toUpperCase(Locale.ROOT);
    }

    public record WireShape(String type, String format) {
    }
}
