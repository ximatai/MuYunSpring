package net.ximatai.muyun.spring.platform.exchange.protocol;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedColumn;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedSheet;

import java.util.ArrayList;
import java.util.List;

public class ExcelExchangeHeaderParser {
    public ParsedSheet parse(String sheetName,
                             List<String> technicalHeader,
                             List<String> displayHeader,
                             List<List<String>> rows) {
        if (technicalHeader == null || technicalHeader.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-exchange-header-parser.exchange-technical-header-must-not-be-empty",
                    "exchange technical header must not be empty"));
        }
        if (displayHeader == null || displayHeader.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-exchange-header-parser.exchange-display-header-must-not-be-empty",
                    "exchange display header must not be empty"));
        }

        String entityAlias = null;
        List<ParsedColumn> columns = new ArrayList<>();
        int columnCount = Math.max(technicalHeader.size(), displayHeader.size());
        for (int index = 0; index < columnCount; index++) {
            String technicalField = index < technicalHeader.size() ? technicalHeader.get(index) : null;
            if (technicalField == null || technicalField.isBlank()) {
                continue;
            }
            ExcelExchangeProtocol.TechnicalField parsedField = ExcelExchangeProtocol.parseTechnicalField(technicalField);
            if (parsedField.entityAlias() != null) {
                if (entityAlias == null) {
                    entityAlias = parsedField.entityAlias();
                } else if (!entityAlias.equals(parsedField.entityAlias())) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.excel-exchange-header-parser.exchange-sheet-mixes-multiple-entity-aliases",
                            "exchange sheet mixes multiple entity aliases: {value1}"), Map.of("value1", String.valueOf(sheetName)));
                }
            }
            String title = index < displayHeader.size() ? displayHeader.get(index) : null;
            columns.add(new ParsedColumn(
                    index,
                    parsedField.entityAlias(),
                    parsedField.fieldName(),
                    title == null || title.isBlank() ? parsedField.fieldName() : title
            ));
        }
        if (entityAlias == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-exchange-header-parser.exchange-sheet-requires-at-least-one-business-field-column",
                    "exchange sheet requires at least one business field column: {value1}"), Map.of("value1", String.valueOf(sheetName)));
        }
        return new ParsedSheet(sheetName, entityAlias, columns, rows);
    }
}
