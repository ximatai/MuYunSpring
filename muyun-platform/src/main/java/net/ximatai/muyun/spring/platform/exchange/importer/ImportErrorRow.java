package net.ximatai.muyun.spring.platform.exchange.importer;

import java.util.LinkedHashMap;

/** Preserves raw cells by technical field name for lossless error-workbook reconstruction. */
public record ImportErrorRow(
        String sheetKey,
        LinkedHashMap<String, String> rawValuesByFieldName,
        String message,
        String groupIdentity
) {
    public ImportErrorRow {
        rawValuesByFieldName = rawValuesByFieldName == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rawValuesByFieldName);
    }

    public static ImportErrorRow of(ParsedImportRow row, String message, String groupIdentity) {
        return new ImportErrorRow(row.sheetKey(), row.rawValuesByFieldName(), message, groupIdentity);
    }
}
