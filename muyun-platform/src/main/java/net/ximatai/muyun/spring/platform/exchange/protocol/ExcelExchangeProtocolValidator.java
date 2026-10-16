package net.ximatai.muyun.spring.platform.exchange.protocol;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.time.PlatformTimeService;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelColumnPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelSheetPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookMeta;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedColumn;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedSheet;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedWorkbook;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public class ExcelExchangeProtocolValidator {
    public void validateWorkbookPlan(ExcelWorkbookPlan workbookPlan) {
        if (workbookPlan == null || workbookPlan.sheets() == null || workbookPlan.sheets().isEmpty()) {
            throw new PlatformException("exchange workbook plan must not be empty");
        }
        int mainCount = 0;
        Set<String> sheetNames = new LinkedHashSet<>();
        for (ExcelSheetPlan sheet : workbookPlan.sheets()) {
            if (sheet == null) {
                throw new PlatformException("exchange workbook plan contains null sheet");
            }
            validateBusinessSheetName(sheet.sheetName());
            if (!sheetNames.add(sheet.sheetName())) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.excel-exchange-protocol-validator.exchange-sheet-name-duplicated",
                        "exchange sheet name duplicated: {value1}"), Map.of("value1", String.valueOf(sheet.sheetName())));
            }
            if (sheet.main()) {
                mainCount++;
            }
            validatePlanColumns(sheet.sheetName(), sheet.columns());
            requireRelateId(sheet.sheetName(), sheet.columns());
        }
        if (mainCount != 1) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-exchange-protocol-validator.exchange-workbook-plan-must-contain-exactly-one-main-sheet",
                    "exchange workbook plan must contain exactly one main sheet"));
        }
    }

    public void validateParsedWorkbook(ParsedWorkbook parsedWorkbook) {
        if (parsedWorkbook == null || parsedWorkbook.sheets() == null || parsedWorkbook.sheets().isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-exchange-protocol-validator.parsed-exchange-workbook-must-not-be-empty",
                    "parsed exchange workbook must not be empty"));
        }
        validateWorkbookMeta(parsedWorkbook.meta());
        Set<String> sheetNames = new LinkedHashSet<>();
        for (ParsedSheet sheet : parsedWorkbook.sheets()) {
            if (sheet == null) {
                throw new PlatformException("parsed exchange workbook contains null sheet");
            }
            validateBusinessSheetName(sheet.sheetName());
            if (!sheetNames.add(sheet.sheetName())) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.excel-exchange-protocol-validator.exchange-sheet-name-duplicated",
                        "exchange sheet name duplicated: {value1}"), Map.of("value1", String.valueOf(sheet.sheetName())));
            }
            validateParsedColumns(sheet.sheetName(), sheet.columns());
            requireParsedRelateId(sheet.sheetName(), sheet.columns());
        }
    }

    public void validateWorkbookMeta(ExcelWorkbookMeta meta) {
        if (meta == null || meta.timeZone() == null || meta.timeZone().isBlank()) {
            return;
        }
        try {
            PlatformTimeService.requireIanaZoneId(meta.timeZone());
        } catch (Exception ex) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-exchange-protocol-validator.exchange-workbook-timezone-is-invalid",
                    "exchange workbook timeZone is invalid: {value1}"), Map.of("value1", String.valueOf(meta.timeZone())), ex);
        }
    }

    private void validatePlanColumns(String sheetName, Iterable<ExcelColumnPlan> columns) {
        Set<String> fieldNames = new LinkedHashSet<>();
        for (ExcelColumnPlan column : columns) {
            if (column == null) {
                throw new PlatformException("exchange sheet contains null column: " + sheetName);
            }
            if (!fieldNames.add(column.fieldName())) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.excel-exchange-protocol-validator.exchange-sheet-field-duplicated",
                        "exchange sheet field duplicated: {value1}.{value2}"), Map.of("value1", String.valueOf(sheetName), "value2", String.valueOf(column.fieldName())));
            }
        }
    }

    private void validateBusinessSheetName(String sheetName) {
        if (sheetName == null || sheetName.isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-exchange-protocol-validator.exchange-sheet-name-must-not-be-blank",
                    "exchange sheet name must not be blank"));
        }
        if (sheetName.length() > 31) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-exchange-protocol-validator.exchange-sheet-name-is-too-long",
                    "exchange sheet name is too long: {value1}"), Map.of("value1", String.valueOf(sheetName)));
        }
        if (ExcelExchangeProtocol.isInternalSheet(sheetName)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-exchange-protocol-validator.exchange-sheet-name-is-reserved",
                    "exchange sheet name is reserved: {value1}"), Map.of("value1", String.valueOf(sheetName)));
        }
        if (sheetName.contains(":") || sheetName.contains("\\") || sheetName.contains("/")
                || sheetName.contains("?") || sheetName.contains("*")
                || sheetName.contains("[") || sheetName.contains("]")) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-exchange-protocol-validator.exchange-sheet-name-contains-invalid-character",
                    "exchange sheet name contains invalid character: {value1}"), Map.of("value1", String.valueOf(sheetName)));
        }
    }

    private void validateParsedColumns(String sheetName, Iterable<ParsedColumn> columns) {
        Set<String> fieldNames = new LinkedHashSet<>();
        for (ParsedColumn column : columns) {
            if (column == null) {
                throw new PlatformException("parsed exchange sheet contains null column: " + sheetName);
            }
            if (!fieldNames.add(column.fieldName())) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.excel-exchange-protocol-validator.exchange-sheet-field-duplicated",
                        "exchange sheet field duplicated: {value1}.{value2}"), Map.of("value1", String.valueOf(sheetName), "value2", String.valueOf(column.fieldName())));
            }
        }
    }

    private void requireRelateId(String sheetName, Iterable<ExcelColumnPlan> columns) {
        for (ExcelColumnPlan column : columns) {
            if (Objects.equals(ExcelExchangeProtocol.RELATE_ID_FIELD, column.fieldName())) {
                return;
            }
        }
        throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.excel-exchange-protocol-validator.exchange-sheet-requires-relateid-technical-column",
                "exchange sheet requires __relateId technical column: {value1}"), Map.of("value1", String.valueOf(sheetName)));
    }

    private void requireParsedRelateId(String sheetName, Iterable<ParsedColumn> columns) {
        for (ParsedColumn column : columns) {
            if (Objects.equals(ExcelExchangeProtocol.RELATE_ID_FIELD, column.fieldName())) {
                return;
            }
        }
        throw PlatformErrors.badRequestText(TextReference.code(
                "platform.rejection.excel-exchange-protocol-validator.exchange-sheet-requires-relateid-technical-column",
                "exchange sheet requires __relateId technical column: {value1}"), Map.of("value1", String.valueOf(sheetName)));
    }
}
