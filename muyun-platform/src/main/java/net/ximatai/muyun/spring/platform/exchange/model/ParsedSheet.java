package net.ximatai.muyun.spring.platform.exchange.model;

import net.ximatai.muyun.spring.common.exception.PlatformException;

import java.util.List;

public record ParsedSheet(
        String sheetName,
        String entityAlias,
        List<ParsedColumn> columns,
        List<List<String>> rows
) {
    public ParsedSheet {
        requireText(sheetName, "sheetName must not be blank");
        requireText(entityAlias, "entityAlias must not be blank");
        if (columns == null || columns.isEmpty()) {
            throw new PlatformException("parsed columns must not be empty");
        }
        columns = List.copyOf(columns);
        rows = copyRows(rows);
    }

    private static List<List<String>> copyRows(List<List<String>> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .map(row -> row == null ? List.<String>of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(row)))
                .toList();
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(message);
        }
    }
}
