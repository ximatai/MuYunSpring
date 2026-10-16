package net.ximatai.muyun.spring.platform.exchange.protocol;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedSheet;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedWorkbook;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ExcelExchangeSheetResolver {
    public ResolvedSheets resolve(ParsedWorkbook workbook, String mainEntityAlias, Set<String> childEntityAliases) {
        if (workbook == null || workbook.sheets() == null || workbook.sheets().isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-exchange-sheet-resolver.parsed-exchange-workbook-must-not-be-empty",
                    "parsed exchange workbook must not be empty"));
        }
        if (mainEntityAlias == null || mainEntityAlias.isBlank()) {
            throw new PlatformException("main entityAlias must not be blank");
        }

        ParsedSheet mainSheet = null;
        Map<String, ParsedSheet> childSheets = new LinkedHashMap<>();
        Set<String> expectedChildEntityAliases = childEntityAliases == null ? Set.of() : childEntityAliases;
        for (ParsedSheet sheet : workbook.sheets()) {
            if (sheet == null) {
                continue;
            }
            if (mainEntityAlias.equals(sheet.entityAlias())) {
                if (mainSheet != null) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.excel-exchange-sheet-resolver.main-sheet-duplicated",
                            "main sheet duplicated: {value1}"), Map.of("value1", String.valueOf(mainEntityAlias)));
                }
                mainSheet = sheet;
                continue;
            }
            if (expectedChildEntityAliases.contains(sheet.entityAlias())) {
                ParsedSheet previous = childSheets.putIfAbsent(sheet.entityAlias(), sheet);
                if (previous != null) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.excel-exchange-sheet-resolver.child-sheet-duplicated",
                            "child sheet duplicated: {value1} ({value2}, {value3})"), Map.of("value1", String.valueOf(sheet.entityAlias()), "value2", String.valueOf(previous.sheetName()), "value3", String.valueOf(sheet.sheetName())));
                }
            }
        }
        if (mainSheet == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-exchange-sheet-resolver.main-sheet-not-found",
                    "main sheet not found: {value1}"), Map.of("value1", String.valueOf(mainEntityAlias)));
        }
        return new ResolvedSheets(mainSheet, childSheets);
    }

    public record ResolvedSheets(
            ParsedSheet mainSheet,
            Map<String, ParsedSheet> childSheets
    ) {
        public ResolvedSheets {
            if (mainSheet == null) {
                throw new PlatformException("mainSheet must not be null");
            }
            childSheets = childSheets == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(childSheets));
        }

        public List<ParsedSheet> childSheetList() {
            return List.copyOf(childSheets.values());
        }
    }
}
