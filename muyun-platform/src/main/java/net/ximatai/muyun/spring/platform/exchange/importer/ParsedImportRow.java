package net.ximatai.muyun.spring.platform.exchange.importer;

import java.util.LinkedHashMap;

/** Raw cells are keyed by technical field name; display titles never identify data. */
public record ParsedImportRow(
        String sheetKey,
        LinkedHashMap<String, String> rawValuesByFieldName,
        LinkedHashMap<String, String> valuesByFieldName,
        LinkedHashMap<String, Object> convertedValues
) {
    public ParsedImportRow(String sheetKey,
                           LinkedHashMap<String, String> rawValuesByFieldName,
                           LinkedHashMap<String, String> valuesByFieldName) {
        this(sheetKey, rawValuesByFieldName, valuesByFieldName, new LinkedHashMap<>());
    }

    public ParsedImportRow {
        rawValuesByFieldName = rawValuesByFieldName == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rawValuesByFieldName);
        valuesByFieldName = valuesByFieldName == null ? new LinkedHashMap<>() : new LinkedHashMap<>(valuesByFieldName);
        convertedValues = convertedValues == null ? new LinkedHashMap<>() : new LinkedHashMap<>(convertedValues);
    }
}
