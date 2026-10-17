package net.ximatai.muyun.spring.platform.exchange.reader;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookMeta;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedColumn;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedSheet;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedWorkbook;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeHeaderParser;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeProtocol;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeProtocolValidator;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExcelWorkbookParser {
    private final ExcelExchangeHeaderParser headerParser;
    private final ExcelExchangeProtocolValidator validator;
    private final boolean preserveText;

    public ExcelWorkbookParser() {
        this(false);
    }
    public ExcelWorkbookParser(boolean preserveText) {
        this(new ExcelExchangeHeaderParser(), new ExcelExchangeProtocolValidator(),preserveText);
    }

    ExcelWorkbookParser(ExcelExchangeHeaderParser headerParser, ExcelExchangeProtocolValidator validator) {
        this(headerParser,validator,false);
    }
    private ExcelWorkbookParser(ExcelExchangeHeaderParser headerParser, ExcelExchangeProtocolValidator validator,boolean preserveText) {
        this.headerParser = headerParser;
        this.validator = validator;
        this.preserveText=preserveText;
    }

    public ParsedWorkbook parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-workbook-parser.exchange-workbook-bytes-must-not-be-empty",
                    "exchange workbook bytes must not be empty"));
        }
        return parse(new ByteArrayInputStream(bytes));
    }

    public ParsedWorkbook parse(InputStream inputStream) {
        if (inputStream == null) {
            throw new PlatformException("exchange workbook input stream must not be null");
        }
        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            return parse(workbook);
        } catch (IOException ex) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-workbook-parser.exchange-workbook-parse-failed",
                    "exchange workbook parse failed"), Map.of(), ex);
        }
    }

    public ParsedWorkbook parse(Workbook workbook) {
        if (workbook == null || workbook.getNumberOfSheets() == 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-workbook-parser.exchange-workbook-must-contain-at-least-one-sheet",
                    "exchange workbook must contain at least one sheet"));
        }

        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        ExcelWorkbookMeta meta = null;
        List<ParsedSheet> sheets = new ArrayList<>();

        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            if (sheet == null) {
                continue;
            }
            String sheetName = sheet.getSheetName();
            if (ExcelExchangeProtocol.isMetaSheet(sheetName)) {
                meta = parseMetaSheet(sheet, formatter, evaluator);
                continue;
            }
            if (ExcelExchangeProtocol.isInternalSheet(sheetName)) {
                continue;
            }
            sheets.add(parseBusinessSheet(sheet, formatter, evaluator));
        }

        ParsedWorkbook parsedWorkbook = new ParsedWorkbook(meta, sheets);
        validator.validateParsedWorkbook(parsedWorkbook);
        return parsedWorkbook;
    }

    private ParsedSheet parseBusinessSheet(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        Row technicalHeaderRow = sheet.getRow(ExcelExchangeProtocol.TECHNICAL_HEADER_ROW_INDEX);
        Row displayHeaderRow = sheet.getRow(ExcelExchangeProtocol.DISPLAY_HEADER_ROW_INDEX);
        if (technicalHeaderRow == null || displayHeaderRow == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-workbook-parser.exchange-sheet-missing-double-headers",
                    "exchange sheet missing double headers: {value1}"), Map.of("value1", String.valueOf(sheet.getSheetName())));
        }

        List<String> technicalHeader = readHeaderRow(technicalHeaderRow, formatter, evaluator);
        List<String> displayHeader = readHeaderRow(displayHeaderRow, formatter, evaluator);
        ParsedSheet parsedHeader = headerParser.parse(sheet.getSheetName(), technicalHeader, displayHeader, List.of());

        List<List<String>> rows = new ArrayList<>();
        for (int rowIndex = ExcelExchangeProtocol.MIN_HEADER_ROW_COUNT; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (isBlankDataRow(row, parsedHeader.columns(), formatter, evaluator)) {
                continue;
            }
            rows.add(readDataRow(row, parsedHeader.columns(), formatter, evaluator));
        }
        return new ParsedSheet(parsedHeader.sheetName(), parsedHeader.entityAlias(), parsedHeader.columns(), rows);
    }

    private ExcelWorkbookMeta parseMetaSheet(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            String key = readCellValue(row.getCell(0), formatter, evaluator);
            key = normalize(key);
            if (key == null) {
                continue;
            }
            values.put(key, normalize(readCellValue(row.getCell(1), formatter, evaluator)));
        }
        if (values.isEmpty()) {
            return null;
        }
        return new ExcelWorkbookMeta(
                values.get(ExcelExchangeProtocol.META_KEY_PROTOCOL_VERSION),
                values.get(ExcelExchangeProtocol.META_KEY_MODULE_ALIAS),
                values.get(ExcelExchangeProtocol.META_KEY_UI_CONFIG_ID),
                values.get(ExcelExchangeProtocol.META_KEY_UI_CONFIG_TITLE),
                values.get(ExcelExchangeProtocol.META_KEY_TIME_ZONE),
                values.get("documentId"), values.get("purpose"), values.get("language")
        );
    }

    private List<String> readHeaderRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        int cellCount = Math.max(row.getLastCellNum(), 0);
        List<String> values = new ArrayList<>(cellCount);
        for (int columnIndex = 0; columnIndex < cellCount; columnIndex++) {
            values.add(normalize(readCellValue(row.getCell(columnIndex), formatter, evaluator)));
        }
        return values;
    }

    private boolean isBlankDataRow(Row row,
                                   List<ParsedColumn> columns,
                                   DataFormatter formatter,
                                   FormulaEvaluator evaluator) {
        if (row == null || columns == null || columns.isEmpty()) {
            return true;
        }
        for (ParsedColumn column : columns) {
            if (column == null) {
                continue;
            }
            if (normalize(readCellValue(row.getCell(column.columnIndex()), formatter, evaluator)) != null) {
                return false;
            }
        }
        return true;
    }

    private List<String> readDataRow(Row row,
                                     List<ParsedColumn> columns,
                                     DataFormatter formatter,
                                     FormulaEvaluator evaluator) {
        int width = columns.stream().mapToInt(ParsedColumn::columnIndex).max().orElse(-1) + 1;
        List<String> values = new ArrayList<>(java.util.Collections.nCopies(width, null));
        for (ParsedColumn column : columns) {
            Cell cell = row == null ? null : row.getCell(column.columnIndex());
            String value=readCellValue(cell,formatter,evaluator);
            values.set(column.columnIndex(), preserveText ? value==null || value.isBlank() ? null : value : normalize(value));
        }
        return values;
    }

    private String readCellValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return null;
        }
        return formatter.formatCellValue(cell, evaluator);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
