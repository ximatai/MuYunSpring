package net.ximatai.muyun.spring.platform.exchange.importer;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicFieldValueSupport;
import net.ximatai.muyun.spring.dynamic.metadata.FieldCompanionRules;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedColumn;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedSheet;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DynamicImportValueConverter {
    private static final DateTimeFormatter LOCAL_DATE_TIME_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ParsedImportRow convert(DynamicImportPlan.SheetPlan sheetPlan,
                                   ParsedSheet parsedSheet,
                                   List<String> row,
                                   ImportTemporalContext temporalContext) {
        if (sheetPlan == null) {
            throw new PlatformException("dynamic import sheet plan must not be null");
        }
        if (parsedSheet == null) {
            throw new PlatformException("parsed sheet must not be null");
        }
        ImportTemporalContext effectiveTemporalContext =
                temporalContext == null ? ImportTemporalContext.UTC : temporalContext;
        LinkedHashMap<String, String> valuesByFieldName = readRowByField(parsedSheet, row);
        LinkedHashMap<String, String> rawValuesByFieldName = new LinkedHashMap<>();
        LinkedHashMap<String, Object> convertedValues = new LinkedHashMap<>();

        for (DynamicImportPlan.FieldPlan field : sheetPlan.fields()) {
            String raw = normalizeText(valuesByFieldName.get(field.fieldName()));
            rawValuesByFieldName.put(field.fieldName(), raw);
            valuesByFieldName.put(field.fieldName(), raw);
        }
        for (DynamicImportPlan.FieldPlan field : sheetPlan.fields()) {
            if (field.relateId() || field.companion()) {
                continue;
            }
            validateCompanionOnlyValue(field, valuesByFieldName);
            Object converted = convertField(field, valuesByFieldName, effectiveTemporalContext);
            if (converted != null) {
                convertedValues.put(field.fieldName(), converted);
                if (field.fieldType() == FieldType.ZONED_TIMESTAMP) {
                    String timeZoneFieldName = FieldCompanionRules.zonedTimestampTimeZoneFieldName(field.fieldName());
                    String normalizedTimeZone = valuesByFieldName.get(timeZoneFieldName);
                    if (normalizedTimeZone != null) {
                        convertedValues.put(timeZoneFieldName, normalizedTimeZone);
                    }
                }
            }
        }
        return new ParsedImportRow(sheetPlan.sheetKey(), rawValuesByFieldName, valuesByFieldName, convertedValues);
    }

    private void validateCompanionOnlyValue(DynamicImportPlan.FieldPlan field,
                                            Map<String, String> valuesByFieldName) {
        if (field.fieldType() != FieldType.ZONED_TIMESTAMP) {
            return;
        }
        String raw = normalizeText(valuesByFieldName.get(field.fieldName()));
        String timeZoneFieldName = FieldCompanionRules.zonedTimestampTimeZoneFieldName(field.fieldName());
        String rawTimeZone = normalizeText(valuesByFieldName.get(timeZoneFieldName));
        if (raw == null && rawTimeZone != null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.dynamic-import-value-converter.zoned-timestamp-timezone-cannot-be-provided-without-value",
                    "zoned timestamp timeZone cannot be provided without value: {value1}"), Map.of("value1", String.valueOf(timeZoneFieldName)));
        }
    }

    private LinkedHashMap<String, String> readRowByField(ParsedSheet parsedSheet, List<String> row) {
        LinkedHashMap<String, String> valuesByField = new LinkedHashMap<>();
        List<String> safeRow = row == null ? List.of() : row;
        for (ParsedColumn column : parsedSheet.columns()) {
            String raw = column.columnIndex() < safeRow.size() ? safeRow.get(column.columnIndex()) : null;
            valuesByField.put(column.fieldName(), normalizeText(raw));
        }
        return valuesByField;
    }

    private Object convertField(DynamicImportPlan.FieldPlan field,
                                Map<String, String> valuesByFieldName,
                                ImportTemporalContext temporalContext) {
        String raw = normalizeText(valuesByFieldName.get(field.fieldName()));
        if (raw == null) {
            return null;
        }
        try {
            return switch (field.fieldType()) {
                case STRING, TEXT, JSON -> raw;
                case INTEGER -> parseInteger(raw);
                case LONG -> parseLong(raw);
                case DECIMAL -> parseDecimal(raw);
                case BOOLEAN -> parseBoolean(raw);
                case DATE -> parseDate(raw);
                case TIMESTAMP -> parseTimestamp(raw, temporalContext.workbookZoneId());
                case ZONED_TIMESTAMP -> parseZonedTimestamp(field, raw, valuesByFieldName);
            };
        } catch (PlatformException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.dynamic-import-value-converter.import-field-value-conversion-failed",
                    "import field value conversion failed: {value1}/{value2}"), Map.of("value1", String.valueOf(field.title()), "value2", String.valueOf(raw)), ex);
        }
    }

    private Integer parseInteger(String raw) {
        requireWholeNumberText(raw, TextReference.code(
                "platform.rejection.dynamic-import-value-converter.whole-integer",
                "integer value must not contain decimals: {value1}"));
        try {
            return Integer.valueOf(raw.replace(",", ""));
        } catch (NumberFormatException ex) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.dynamic-import-value-converter.integer-value-format-is-invalid",
                    "integer value format is invalid: {value1}"), Map.of("value1", String.valueOf(raw)), ex);
        }
    }

    private Long parseLong(String raw) {
        requireWholeNumberText(raw, TextReference.code(
                "platform.rejection.dynamic-import-value-converter.whole-long",
                "long value must not contain decimals: {value1}"));
        try {
            return Long.valueOf(raw.replace(",", ""));
        } catch (NumberFormatException ex) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.dynamic-import-value-converter.long-value-format-is-invalid",
                    "long value format is invalid: {value1}"), Map.of("value1", String.valueOf(raw)), ex);
        }
    }

    private void requireWholeNumberText(String raw, TextReference source) {
        if (!raw.replace(",", "").matches("[+-]?\\d+")) {
            throw PlatformErrors.badRequestText(source, Map.of("value1", raw));
        }
    }

    private BigDecimal parseDecimal(String raw) {
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException ex) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.dynamic-import-value-converter.decimal-value-format-is-invalid",
                    "decimal value format is invalid: {value1}"), Map.of("value1", String.valueOf(raw)), ex);
        }
    }

    private Boolean parseBoolean(String raw) {
        if ("true".equalsIgnoreCase(raw) || "1".equals(raw)
                || "yes".equalsIgnoreCase(raw) || "y".equalsIgnoreCase(raw)
                || "是".equals(raw) || "开".equals(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw) || "0".equals(raw)
                || "no".equalsIgnoreCase(raw) || "n".equalsIgnoreCase(raw)
                || "否".equals(raw) || "关".equals(raw)) {
            return Boolean.FALSE;
        }
        throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.dynamic-import-value-converter.boolean-value-format-is-invalid",
                "boolean value format is invalid: {value1}"), Map.of("value1", String.valueOf(raw)));
    }

    private LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException ex) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.dynamic-import-value-converter.date-value-format-is-invalid",
                    "date value format is invalid: {value1}"), Map.of("value1", String.valueOf(raw)), ex);
        }
    }

    private Instant parseTimestamp(String raw, ZoneId zoneId) {
        if (raw.endsWith("Z")) {
            return DynamicFieldValueSupport.normalize(FieldType.TIMESTAMP, raw) instanceof Instant instant
                    ? instant
                    : null;
        }
        try {
            LocalDateTime localDateTime = LocalDateTime.parse(raw, LOCAL_DATE_TIME_SECONDS);
            return localDateTime.atZone(zoneId).toInstant();
        } catch (DateTimeParseException ex) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.dynamic-import-value-converter.timestamp-value-format-is-invalid",
                    "timestamp value format is invalid: {value1}"), Map.of("value1", String.valueOf(raw)), ex);
        }
    }

    private Instant parseZonedTimestamp(DynamicImportPlan.FieldPlan field,
                                        String raw,
                                        Map<String, String> valuesByFieldName) {
        String timeZoneFieldName = FieldCompanionRules.zonedTimestampTimeZoneFieldName(field.fieldName());
        String rawTimeZone = normalizeText(valuesByFieldName.get(timeZoneFieldName));
        if (rawTimeZone == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.dynamic-import-value-converter.zoned-timestamp-requires-companion-timezone-field",
                    "zoned timestamp requires companion timeZone field: {value1}"), Map.of("value1", String.valueOf(timeZoneFieldName)));
        }
        String normalizedTimeZone;
        try {
            normalizedTimeZone = DynamicFieldValueSupport.normalizeTimeZone(rawTimeZone);
        } catch (RuntimeException ex) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.dynamic-import-value-converter.timezone-value-format-is-invalid",
                    "timeZone value format is invalid: {value1}"), Map.of("value1", String.valueOf(rawTimeZone)), ex);
        }
        Instant instant = parseTimestamp(raw, ZoneId.of(normalizedTimeZone));
        valuesByFieldName.put(timeZoneFieldName, normalizedTimeZone);
        return instant;
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
