package net.ximatai.muyun.spring.platform.exchange.writer;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelColumnPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelSheetPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelValueType;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookMeta;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookPlan;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeProtocol;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeProtocolValidator;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class ExcelWorkbookPlanWriter {
    private static final int FIRST_DATA_ROW_INDEX = ExcelExchangeProtocol.MIN_HEADER_ROW_COUNT;
    private static final int MIN_COLUMN_WIDTH = 12;
    private static final int MAX_COLUMN_WIDTH = 40;
    private static final int DEFAULT_VALIDATION_ROWS = 1000;

    private final ExcelExchangeProtocolValidator validator;

    public ExcelWorkbookPlanWriter() {
        this(new ExcelExchangeProtocolValidator());
    }

    ExcelWorkbookPlanWriter(ExcelExchangeProtocolValidator validator) {
        this.validator = validator;
    }

    public byte[] writeToBytes(ExcelWorkbookPlan plan) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            write(plan, out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-workbook-plan-writer.exchange-workbook-write-failed",
                    "exchange workbook write failed"), Map.of(), ex);
        }
    }

    public void write(ExcelWorkbookPlan plan, OutputStream out) {
        if (out == null) {
            throw new PlatformException("exchange workbook output stream must not be null");
        }
        validator.validateWorkbookPlan(plan);
        validator.validateWorkbookMeta(plan.meta());

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            DataStyles dataStyles = createDataStyles(workbook);
            for (ExcelSheetPlan sheetPlan : plan.sheets()) {
                Sheet sheet = workbook.createSheet(sheetPlan.sheetName());
                sheet.createFreezePane(0, FIRST_DATA_ROW_INDEX);
                writeHeaders(sheet, sheetPlan);
                writeRows(sheet, sheetPlan.columns(), sheetPlan.rows(), dataStyles);
                applyColumnWidths(sheet, sheetPlan);
                if (plan.meta() != null && "2".equals(plan.meta().protocolVersion())) {
                    for (int index = 0; index < sheetPlan.columns().size(); index++) {
                        String field = sheetPlan.columns().get(index).fieldName();
                        if (java.util.Set.of("__exchangeRow", "__inputIdentity", "__originalId").contains(field))
                            sheet.setColumnHidden(index, true);
                    }
                }
            }
            writeOptionValidations(workbook, plan);
            writeMetaSheet(workbook, plan.meta());
            workbook.write(out);
            out.flush();
        } catch (IOException ex) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.excel-workbook-plan-writer.exchange-workbook-write-failed",
                    "exchange workbook write failed"), Map.of(), ex);
        }
    }

    private void writeHeaders(Sheet sheet, ExcelSheetPlan sheetPlan) {
        Row technicalRow = sheet.createRow(ExcelExchangeProtocol.TECHNICAL_HEADER_ROW_INDEX);
        Row displayRow = sheet.createRow(ExcelExchangeProtocol.DISPLAY_HEADER_ROW_INDEX);
        for (int columnIndex = 0; columnIndex < sheetPlan.columns().size(); columnIndex++) {
            ExcelColumnPlan column = sheetPlan.columns().get(columnIndex);
            technicalRow.createCell(columnIndex).setCellValue(
                    ExcelExchangeProtocol.composeTechnicalField(sheetPlan.entityAlias(), column.fieldName())
            );
            displayRow.createCell(columnIndex).setCellValue(displayTitle(column));
        }
    }

    private String displayTitle(ExcelColumnPlan column) {
        return column.required() ? "*" + column.title() : column.title();
    }

    private DataStyles createDataStyles(XSSFWorkbook workbook) {
        DataFormat dataFormat = workbook.createDataFormat();
        CellStyle date = workbook.createCellStyle();
        date.setDataFormat(dataFormat.getFormat("yyyy-mm-dd"));
        CellStyle dateTime = workbook.createCellStyle();
        dateTime.setDataFormat(dataFormat.getFormat("yyyy-mm-dd hh:mm:ss"));
        return new DataStyles(date, dateTime);
    }

    private void writeRows(Sheet sheet, List<ExcelColumnPlan> columns, List<List<Object>> rows, DataStyles dataStyles) {
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = sheet.createRow(FIRST_DATA_ROW_INDEX + rowIndex);
            List<Object> values = rows.get(rowIndex);
            int columnCount = Math.min(values.size(), columns.size());
            for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                Object value = values.get(columnIndex);
                if (value == null) {
                    continue;
                }
                ExcelValueType valueType = columns.get(columnIndex).valueType();
                writeCell(row.createCell(columnIndex), value, valueType, dataStyles);
            }
        }
    }

    private void writeCell(Cell cell, Object value, ExcelValueType valueType, DataStyles dataStyles) {
        switch (valueType == null ? ExcelValueType.TEXT : valueType) {
            case NUMBER -> writeNumberCell(cell, value);
            case BOOLEAN -> writeBooleanCell(cell, value);
            case DATE -> writeDateCell(cell, value, dataStyles);
            case DATE_TIME -> writeDateTimeCell(cell, value, dataStyles);
            case DATE_TIME_WITH_TIME_ZONE, DATE_TIME_WITH_TIME_ZONE_RANGE, TEXT -> cell.setCellValue(String.valueOf(value));
        }
    }

    private void writeNumberCell(Cell cell, Object value) {
        Double number = toDouble(value);
        boolean exact = false;
        try {
            var decimal = new java.math.BigDecimal(String.valueOf(value).trim());
            exact = number != null && Double.isFinite(number) && decimal.stripTrailingZeros().precision() <= 15
                    && java.math.BigDecimal.valueOf(number).compareTo(decimal) == 0;
        } catch (NumberFormatException invalid) { /* Preserve the actual cell text rather than coercing it. */ }
        if (!exact) {
            cell.setCellValue(String.valueOf(value));
            return;
        }
        cell.setCellValue(number);
    }

    private void writeBooleanCell(Cell cell, Object value) {
        Boolean bool = toBoolean(value);
        if (bool == null) {
            cell.setCellValue(String.valueOf(value));
            return;
        }
        cell.setCellValue(bool);
    }

    private void writeDateCell(Cell cell, Object value, DataStyles dataStyles) {
        if (value instanceof LocalDate localDate) {
            cell.setCellValue(localDate);
            cell.setCellStyle(dataStyles.date());
            return;
        }
        cell.setCellValue(String.valueOf(value));
    }

    private void writeDateTimeCell(Cell cell, Object value, DataStyles dataStyles) {
        if (value instanceof LocalDateTime localDateTime) {
            cell.setCellValue(localDateTime);
            cell.setCellStyle(dataStyles.dateTime());
            return;
        }
        cell.setCellValue(String.valueOf(value));
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text.replace(",", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        if ("true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text) || "y".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text) || "no".equalsIgnoreCase(text) || "n".equalsIgnoreCase(text)) {
            return false;
        }
        return null;
    }

    private void applyColumnWidths(Sheet sheet, ExcelSheetPlan sheetPlan) {
        for (int columnIndex = 0; columnIndex < sheetPlan.columns().size(); columnIndex++) {
            ExcelColumnPlan column = sheetPlan.columns().get(columnIndex);
            int maxLength = Math.max(
                    ExcelExchangeProtocol.composeTechnicalField(sheetPlan.entityAlias(), column.fieldName()).length(),
                    displayTitle(column).length()
            );
            for (List<Object> row : sheetPlan.rows()) {
                if (columnIndex < row.size() && row.get(columnIndex) != null) {
                    maxLength = Math.max(maxLength, String.valueOf(row.get(columnIndex)).length());
                }
            }
            int width = Math.max(MIN_COLUMN_WIDTH, Math.min(MAX_COLUMN_WIDTH, maxLength + 4));
            sheet.setColumnWidth(columnIndex, width * 256);
        }
    }

    private void writeOptionValidations(XSSFWorkbook workbook, ExcelWorkbookPlan plan) {
        Sheet optionsSheet = null;
        int optionColumnIndex = 0;
        for (ExcelSheetPlan sheetPlan : plan.sheets()) {
            Sheet businessSheet = workbook.getSheet(sheetPlan.sheetName());
            for (int columnIndex = 0; columnIndex < sheetPlan.columns().size(); columnIndex++) {
                ExcelColumnPlan column = sheetPlan.columns().get(columnIndex);
                if (column.dropdownOptions().isEmpty()) {
                    continue;
                }
                if (optionsSheet == null) {
                    optionsSheet = workbook.createSheet(ExcelExchangeProtocol.OPTIONS_SHEET_NAME);
                }
                String rangeName = "exchange_options_" + optionColumnIndex;
                writeOptionColumn(optionsSheet, optionColumnIndex, sheetPlan, column);
                createOptionName(workbook, rangeName, optionColumnIndex, column.dropdownOptions().size());
                addOptionValidation(businessSheet, sheetPlan, columnIndex, rangeName);
                optionColumnIndex++;
            }
        }
        if (optionsSheet != null) {
            workbook.setSheetHidden(workbook.getSheetIndex(optionsSheet), true);
        }
    }

    private void writeOptionColumn(Sheet optionsSheet,
                                   int optionColumnIndex,
                                   ExcelSheetPlan sheetPlan,
                                   ExcelColumnPlan column) {
        Row header = row(optionsSheet, 0);
        header.createCell(optionColumnIndex)
                .setCellValue(sheetPlan.entityAlias() + "." + column.fieldName());
        for (int index = 0; index < column.dropdownOptions().size(); index++) {
            row(optionsSheet, index + 1).createCell(optionColumnIndex)
                    .setCellValue(column.dropdownOptions().get(index));
        }
        optionsSheet.setColumnWidth(optionColumnIndex, Math.max(MIN_COLUMN_WIDTH, column.title().length() + 4) * 256);
    }

    private Row row(Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        return row == null ? sheet.createRow(rowIndex) : row;
    }

    private void createOptionName(XSSFWorkbook workbook, String rangeName, int optionColumnIndex, int optionCount) {
        Name name = workbook.createName();
        name.setNameName(rangeName);
        String column = columnName(optionColumnIndex);
        name.setRefersToFormula("'" + ExcelExchangeProtocol.OPTIONS_SHEET_NAME + "'!$"
                + column + "$2:$" + column + "$" + (optionCount + 1));
    }

    private void addOptionValidation(Sheet sheet, ExcelSheetPlan sheetPlan, int columnIndex, String rangeName) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createFormulaListConstraint(rangeName);
        int validationEndRow = FIRST_DATA_ROW_INDEX + Math.max(DEFAULT_VALIDATION_ROWS, sheetPlan.rows().size()) - 1;
        CellRangeAddressList range = new CellRangeAddressList(
                FIRST_DATA_ROW_INDEX,
                validationEndRow,
                columnIndex,
                columnIndex
        );
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    private String columnName(int zeroBasedColumnIndex) {
        int column = zeroBasedColumnIndex + 1;
        StringBuilder name = new StringBuilder();
        while (column > 0) {
            int remainder = (column - 1) % 26;
            name.insert(0, (char) ('A' + remainder));
            column = (column - 1) / 26;
        }
        return name.toString();
    }

    private void writeMetaSheet(XSSFWorkbook workbook, ExcelWorkbookMeta meta) {
        Sheet sheet = workbook.createSheet(ExcelExchangeProtocol.META_SHEET_NAME);
        writeMetaRow(sheet, 0, ExcelExchangeProtocol.META_KEY_PROTOCOL_VERSION, meta == null ? null : meta.protocolVersion());
        writeMetaRow(sheet, 1, ExcelExchangeProtocol.META_KEY_MODULE_ALIAS, meta == null ? null : meta.moduleAlias());
        writeMetaRow(sheet, 2, ExcelExchangeProtocol.META_KEY_UI_CONFIG_ID, meta == null ? null : meta.uiConfigId());
        writeMetaRow(sheet, 3, ExcelExchangeProtocol.META_KEY_UI_CONFIG_TITLE, meta == null ? null : meta.uiConfigTitle());
        writeMetaRow(sheet, 4, ExcelExchangeProtocol.META_KEY_TIME_ZONE, meta == null ? null : meta.timeZone());
        writeMetaRow(sheet, 5, "documentId", meta == null ? null : meta.documentId());
        writeMetaRow(sheet, 6, "purpose", meta == null ? null : meta.purpose());
        writeMetaRow(sheet, 7, "language", meta == null ? null : meta.language());
        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 40 * 256);
        workbook.setSheetHidden(workbook.getSheetIndex(sheet), true);
    }

    private void writeMetaRow(Sheet sheet, int rowIndex, String key, String value) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(key);
        row.createCell(1).setCellValue(value == null ? "" : value);
    }

    private record DataStyles(CellStyle date, CellStyle dateTime) {
    }
}
