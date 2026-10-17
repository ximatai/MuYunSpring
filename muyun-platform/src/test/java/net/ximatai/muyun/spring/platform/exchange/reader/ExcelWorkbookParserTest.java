package net.ximatai.muyun.spring.platform.exchange.reader;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelColumnPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelSheetPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelValueType;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookMeta;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedSheet;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedWorkbook;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeProtocol;
import net.ximatai.muyun.spring.platform.exchange.writer.ExcelWorkbookPlanWriter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExcelWorkbookParserTest {
    private final ExcelWorkbookPlanWriter writer = new ExcelWorkbookPlanWriter();
    private final ExcelWorkbookParser parser = new ExcelWorkbookParser();

    @Test
    void shouldPreservePhysicalColumnPositionsWhenAnOptionalHeaderIsOmitted() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("main");
            var technical = sheet.createRow(0);
            technical.createCell(0).setCellValue("__relateId");
            technical.createCell(2).setCellValue("main.quantity");
            sheet.createRow(1).createCell(2).setCellValue("Quantity");
            var row = sheet.createRow(2);
            row.createCell(0).setCellValue("R-1");
            row.createCell(1).setCellValue("Ignored column");
            row.createCell(2).setCellValue(0);
            var parsed = parser.parse(workbook).sheets().getFirst();
            assertThat(parsed.columns()).extracting("columnIndex").containsExactly(0, 2);
            assertThat(parsed.rows().getFirst()).containsExactly("R-1", null, "0");
        }
    }

    @Test
    void shouldParseWriterGeneratedMainAndChildWorkbook() {
        byte[] bytes = writer.writeToBytes(orderPlan());

        ParsedWorkbook workbook = parser.parse(bytes);

        assertThat(workbook.meta()).isEqualTo(new ExcelWorkbookMeta(
                "1",
                "sales.order",
                "order-import",
                "Order Import",
                "Asia/Shanghai"
        ));
        assertThat(workbook.sheets()).hasSize(2);

        ParsedSheet order = workbook.sheets().get(0);
        assertThat(order.sheetName()).isEqualTo("Order");
        assertThat(order.entityAlias()).isEqualTo("order_main");
        assertThat(order.columns())
                .extracting("columnIndex", "entityAlias", "fieldName", "title")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, null, "relateId", "关联标识"),
                        org.assertj.core.groups.Tuple.tuple(1, "order_main", "orderNo", "*Order No"),
                        org.assertj.core.groups.Tuple.tuple(2, "order_main", "amount", "Amount"),
                        org.assertj.core.groups.Tuple.tuple(3, "order_main", "enabled", "Enabled"),
                        org.assertj.core.groups.Tuple.tuple(4, "order_main", "signedDate", "Signed Date"),
                        org.assertj.core.groups.Tuple.tuple(5, "order_main", "submittedAt", "Submitted At"),
                        org.assertj.core.groups.Tuple.tuple(6, "order_main", "remark", "Remark")
                );
        assertThat(order.rows()).containsExactly(List.of(
                "R-1",
                "SO-001",
                "12.5",
                "TRUE",
                "2026-06-08",
                "2026-06-08 09:30:00",
                "first order"
        ));

        ParsedSheet line = workbook.sheets().get(1);
        assertThat(line.sheetName()).isEqualTo("Order Line");
        assertThat(line.entityAlias()).isEqualTo("order_line");
        assertThat(line.columns())
                .extracting("columnIndex", "entityAlias", "fieldName", "title")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, null, "relateId", "关联标识"),
                        org.assertj.core.groups.Tuple.tuple(1, "order_line", "sku", "SKU"),
                        org.assertj.core.groups.Tuple.tuple(2, "order_line", "qty", "Qty")
                );
        assertThat(line.rows()).containsExactly(List.of("R-1", "SKU-01", "2"));
    }

    @Test
    void shouldParseHiddenMetaSheet() {
        byte[] bytes = writer.writeToBytes(orderPlan());

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            int metaIndex = workbook.getSheetIndex(ExcelExchangeProtocol.META_SHEET_NAME);
            assertThat(workbook.isSheetHidden(metaIndex)).isTrue();

            ParsedWorkbook parsedWorkbook = parser.parse(workbook);

            assertThat(parsedWorkbook.meta().moduleAlias()).isEqualTo("sales.order");
            assertThat(parsedWorkbook.meta().timeZone()).isEqualTo("Asia/Shanghai");
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void shouldSkipBlankDataRowsAndInternalExchangeSheets() {
        byte[] bytes = writer.writeToBytes(orderPlan());

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet order = workbook.getSheet("Order");
            order.createRow(3).createCell(0).setCellValue("   ");
            order.getRow(3).createCell(1).setCellValue("  ");

            Sheet internal = workbook.createSheet("_exchange_options");
            internal.createRow(0).createCell(0).setCellValue("reserved");

            ParsedWorkbook parsedWorkbook = parser.parse(workbook);

            assertThat(parsedWorkbook.sheets()).extracting(ParsedSheet::sheetName)
                    .containsExactly("Order", "Order Line");
            assertThat(parsedWorkbook.sheets().get(0).rows()).hasSize(1);
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void shouldNotSkipBusinessSheetWithExchangePrefix() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet prefixed = workbook.createSheet("_exchange_business");
            writeHeaders(prefixed, "__relateId", "exchange_table.code");
            Row data = prefixed.createRow(2);
            data.createCell(0).setCellValue("R-1");
            data.createCell(1).setCellValue("B-001");

            ParsedWorkbook parsedWorkbook = parser.parse(workbook);

            assertThat(parsedWorkbook.sheets()).hasSize(1);
            assertThat(parsedWorkbook.sheets().getFirst().sheetName()).isEqualTo("_exchange_business");
            assertThat(parsedWorkbook.sheets().getFirst().rows()).containsExactly(List.of("R-1", "B-001"));
        }
    }

    @Test
    void shouldParseMetaIgnoringBlankKeyAndNormalizingValues() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet order = workbook.createSheet("Order");
            writeHeaders(order, "__relateId", "order_main.orderNo");
            Row data = order.createRow(2);
            data.createCell(0).setCellValue("R-1");
            data.createCell(1).setCellValue("SO-001");

            Sheet meta = workbook.createSheet(ExcelExchangeProtocol.META_SHEET_NAME);
            meta.createRow(0).createCell(0).setCellValue("   ");
            Row moduleAlias = meta.createRow(1);
            moduleAlias.createCell(0).setCellValue(" moduleAlias ");
            moduleAlias.createCell(1).setCellValue(" sales.order ");

            ParsedWorkbook parsedWorkbook = parser.parse(workbook);

            assertThat(parsedWorkbook.meta().moduleAlias()).isEqualTo("sales.order");
        }
    }

    @Test
    void shouldRejectWorkbookWithoutSheets() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            assertThatThrownBy(() -> parser.parse(workbook))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("at least one sheet");
        }
    }

    @Test
    void shouldRejectSheetMissingDoubleHeaders() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Order");
            sheet.createRow(0).createCell(0).setCellValue("__relateId");

            assertThatThrownBy(() -> parser.parse(workbook))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("missing double headers");
        }
    }

    @Test
    void shouldRejectInvalidTechnicalHeader() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Order");
            writeHeaders(sheet, "__relateId", "orderNo");

            assertThatThrownBy(() -> parser.parse(workbook))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("invalid exchange technical field");
        }
    }

    @Test
    void shouldRejectSheetMissingRelateIdThroughValidator() throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Order");
            writeHeaders(sheet, "order_main.orderNo");

            assertThatThrownBy(() -> parser.parse(workbook))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("__relateId");
        }
    }

    @Test
    void shouldParseInputStream() {
        byte[] bytes = writer.writeToBytes(orderPlan());

        ParsedWorkbook parsedWorkbook = parser.parse(new ByteArrayInputStream(bytes));

        assertThat(parsedWorkbook.sheets()).hasSize(2);
    }

    private ExcelWorkbookPlan orderPlan() {
        return new ExcelWorkbookPlan(
                new ExcelWorkbookMeta("1", "sales.order", "order-import", "Order Import", "Asia/Shanghai"),
                List.of(
                        new ExcelSheetPlan(
                                "Order",
                                "order_main",
                                true,
                                List.of(
                                        new ExcelColumnPlan("relateId", "关联标识"),
                                        new ExcelColumnPlan("orderNo", "Order No", true),
                                        new ExcelColumnPlan("amount", "Amount", ExcelValueType.NUMBER),
                                        new ExcelColumnPlan("enabled", "Enabled", ExcelValueType.BOOLEAN),
                                        new ExcelColumnPlan("signedDate", "Signed Date", ExcelValueType.DATE),
                                        new ExcelColumnPlan("submittedAt", "Submitted At", ExcelValueType.DATE_TIME),
                                        new ExcelColumnPlan("remark", "Remark")
                                ),
                                List.of(List.of(
                                        "R-1",
                                        "SO-001",
                                        12.5,
                                        true,
                                        LocalDate.parse("2026-06-08"),
                                        LocalDateTime.parse("2026-06-08T09:30:00"),
                                        "first order"
                                ))
                        ),
                        new ExcelSheetPlan(
                                "Order Line",
                                "order_line",
                                false,
                                List.of(
                                        new ExcelColumnPlan("relateId", "关联标识"),
                                        new ExcelColumnPlan("sku", "SKU"),
                                        new ExcelColumnPlan("qty", "Qty", ExcelValueType.NUMBER)
                                ),
                                List.of(List.of("R-1", "SKU-01", 2))
                        )
                )
        );
    }

    private void writeHeaders(Sheet sheet, String... technicalHeaders) {
        Row technical = sheet.createRow(ExcelExchangeProtocol.TECHNICAL_HEADER_ROW_INDEX);
        Row display = sheet.createRow(ExcelExchangeProtocol.DISPLAY_HEADER_ROW_INDEX);
        for (int columnIndex = 0; columnIndex < technicalHeaders.length; columnIndex++) {
            technical.createCell(columnIndex).setCellValue(technicalHeaders[columnIndex]);
            display.createCell(columnIndex).setCellValue("Column " + columnIndex);
        }
    }

}
