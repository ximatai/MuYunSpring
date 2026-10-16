package net.ximatai.muyun.spring.platform.exchange.importer;

import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelColumnPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelSheetPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookMeta;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedColumn;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedSheet;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedWorkbook;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeProtocol;
import net.ximatai.muyun.spring.platform.exchange.reader.ExcelWorkbookParser;
import net.ximatai.muyun.spring.platform.exchange.writer.ExcelWorkbookPlanWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImportWorkbookGrouperTest {
    private final ImportWorkbookGrouper grouper = new ImportWorkbookGrouper();

    @Test
    void shouldGroupMainAndChildRowsByRelateId() {
        GroupedWorkbook grouped = grouper.group(plan(ImportDuplicateStrategy.ERROR), workbook(
                mainRows(List.of("R-1", "SO-1"), List.of("R-2", "SO-2")),
                childRows(List.of("R-1", "SKU-1", "2"), List.of("R-2", "SKU-2", "3"))
        ));

        assertThat(grouped.errorRows()).isEmpty();
        assertThat(grouped.groups()).containsOnlyKeys("R-1", "R-2");
        assertThat(grouped.groups().get("R-1").mainRow().valuesByFieldName()).containsEntry("orderNo", "SO-1");
        assertThat(grouped.groups().get("R-1").childRowsBySheetKey().get("orderLine"))
                .hasSize(1)
                .first()
                .extracting(row -> row.valuesByFieldName().get("sku"))
                .isEqualTo("SKU-1");
    }

    @Test
    void shouldRejectBlankAndDuplicatedMainRelateId() {
        GroupedWorkbook grouped = grouper.group(plan(ImportDuplicateStrategy.ERROR), workbook(
                mainRows(List.of("", "SO-0"), List.of("R-1", "SO-1"), List.of("R-1", "SO-2")),
                childRows()
        ));

        assertThat(grouped.groups()).isEmpty();
        assertThat(grouped.errorRows()).extracting(ImportErrorRow::message)
                .contains(
                        "主表关联标识不能为空",
                        "主表关联标识重复: R-1",
                        "主表关联标识重复: R-1"
                );
    }

    @Test
    void shouldRejectBlankAndDuplicatedMainMatchKey() {
        GroupedWorkbook grouped = grouper.group(plan(ImportDuplicateStrategy.ERROR), workbook(
                mainRows(List.of("R-1", ""), List.of("R-2", "SO-1"), List.of("R-3", "SO-1")),
                childRows()
        ));

        assertThat(grouped.groups()).isEmpty();
        assertThat(grouped.errorRows()).extracting(ImportErrorRow::message)
                .contains(
                        "主表匹配字段不能为空",
                        "主表 Excel 内匹配键重复: SO-1",
                        "主表 Excel 内匹配键重复: SO-1"
                );
    }

    @Test
    void shouldReportChildRelateIdErrorsAndMissingCoverage() {
        GroupedWorkbook grouped = grouper.group(plan(ImportDuplicateStrategy.ERROR), workbook(
                mainRows(List.of("R-1", "SO-1"), List.of("R-2", "SO-2")),
                childRows(List.of("", "SKU-0", "1"), List.of("R-X", "SKU-X", "1"), List.of("R-1", "SKU-1", "1"))
        ));

        assertThat(grouped.groups()).containsOnlyKeys("R-1", "R-2");
        assertThat(grouped.errorRows()).extracting(ImportErrorRow::message)
                .contains(
                        "子表关联标识不能为空",
                        "子表未找到对应主表关联标识: R-X",
                        "子表未覆盖全部主表记录: Order Line"
                );
    }

    @Test
    void shouldTreatCoverageOnlyChildRowAsCoveredWithoutAddingChildRow() {
        GroupedWorkbook grouped = grouper.group(plan(ImportDuplicateStrategy.ERROR), workbook(
                mainRows(List.of("R-1", "SO-1")),
                childRows(List.of("R-1", "", ""))
        ));

        assertThat(grouped.errorRows()).isEmpty();
        assertThat(grouped.groups().get("R-1").isChildSheetCovered("orderLine")).isTrue();
        assertThat(grouped.groups().get("R-1").childRowsBySheetKey()).doesNotContainKey("orderLine");
    }

    @Test
    void shouldValidateChildMatchKeyForErrorStrategy() {
        GroupedWorkbook grouped = grouper.group(plan(ImportDuplicateStrategy.ERROR), workbook(
                mainRows(List.of("R-1", "SO-1")),
                childRows(List.of("R-1", "", "1"), List.of("R-1", "SKU-1", "1"), List.of("R-1", "SKU-1", "2"))
        ));

        assertThat(grouped.groups().get("R-1").childRowsBySheetKey().get("orderLine")).hasSize(1);
        assertThat(grouped.errorRows()).extracting(ImportErrorRow::message)
                .contains(
                        "子表匹配字段不能为空",
                        "子表 Excel 内匹配键重复: SKU-1"
                );
    }

    @Test
    void shouldAllowDuplicatedChildMatchKeyForSkipOrOverwriteStrategy() {
        GroupedWorkbook grouped = grouper.group(plan(ImportDuplicateStrategy.SKIP), workbook(
                mainRows(List.of("R-1", "SO-1")),
                childRows(List.of("R-1", "SKU-1", "1"), List.of("R-1", "SKU-1", "2"))
        ));

        assertThat(grouped.errorRows()).isEmpty();
        assertThat(grouped.groups().get("R-1").childRowsBySheetKey().get("orderLine")).hasSize(2);
    }

    @Test
    void conversionRejectionsKeepSourcesAndRawCellsThroughTheErrorWorkbook() throws Exception {
        String raw = "原文 {value1}";
        var plan = mainIntegerPlan();
        var grouped = grouper.group(plan, workbook(mainRows(List.of("R-1", raw)), childRows()));
        assertThat(grouped.groups()).isEmpty();
        var error = grouped.errorRows().getFirst();
        assertThat(error.message()).isEqualTo("integer value must not contain decimals: " + raw);
        assertThat(error.rawValuesByFieldName()).containsValue(raw);
        var catalog = net.ximatai.muyun.spring.common.i18n.LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        var texts = net.ximatai.muyun.spring.common.i18n.TextResources.load(getClass().getClassLoader(), catalog);
        for (String language : List.of("en", "zh-CN", "en")) {
            try (var scope = net.ximatai.muyun.spring.common.i18n.LanguageContext.use(language)) {
                String expected = (language.equals("en") ? "An integer value is required: " : "必须是整数值：") + raw;
                assertThat(error.presentedMessage(texts)).isEqualTo(expected);
                var output = new DynamicImportErrorWorkbookBuilder(texts).build(plan, grouped.errorRows());
                var sheet = output.sheets().getFirst();
                assertThat(sheet.columns()).extracting(ExcelColumnPlan::fieldName)
                        .containsExactly(ExcelExchangeProtocol.RELATE_ID_FIELD, "orderNo", "errorReason");
                assertThat(sheet.rows().getFirst()).containsExactly("R-1", raw, expected);
                try (var excel = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(
                        new ExcelWorkbookPlanWriter().writeToBytes(output)))) {
                    var written = excel.getSheet(sheet.sheetName());
                    assertThat(written.getRow(0).getCell(0).getStringCellValue()).isEqualTo("__relateId");
                    assertThat(written.getRow(0).getCell(1).getStringCellValue()).isEqualTo("order.orderNo");
                    assertThat(written.getRow(0).getCell(2).getStringCellValue()).isEqualTo("order.errorReason");
                    assertThat(written.getRow(2).getCell(1).getStringCellValue()).isEqualTo(raw);
                    assertThat(written.getRow(2).getCell(2).getStringCellValue()).isEqualTo(expected);
                }
            }
        }
        assertThat(error.rawValuesByFieldName()).containsValue(raw);
    }

    @Test
    void shouldPutMainConversionErrorIntoErrorRowsAndSkipGroup() {
        GroupedWorkbook grouped = grouper.group(mainIntegerPlan(), workbook(
                mainRows(List.of("R-1", "not-int"), List.of("R-2", "200")),
                childRows()
        ));

        assertThat(grouped.groups()).containsOnlyKeys("R-2");
        assertThat(grouped.errorRows()).extracting(ImportErrorRow::message)
                .anyMatch(message -> message.contains("integer value"));
    }

    @Test
    void shouldPutChildConversionErrorIntoErrorRowsAndKeepOtherChildRows() {
        GroupedWorkbook grouped = grouper.group(plan(ImportDuplicateStrategy.ERROR), workbook(
                mainRows(List.of("R-1", "SO-1")),
                childRows(List.of("R-1", "SKU-BAD", "bad"), List.of("R-1", "SKU-1", "2"))
        ));

        assertThat(grouped.groups()).containsOnlyKeys("R-1");
        assertThat(grouped.groups().get("R-1").childRowsBySheetKey().get("orderLine"))
                .hasSize(1)
                .first()
                .extracting(row -> row.convertedValues().get("qty"))
                .isEqualTo(2);
        assertThat(grouped.errorRows()).extracting(ImportErrorRow::message)
                .anyMatch(message -> message.contains("integer value"));
    }

    @Test
    void shouldRoundTripWriterParserIntoPlanBuilderAndGrouper() {
        ExcelWorkbookPlan workbookPlan = new ExcelWorkbookPlan(
                new ExcelWorkbookMeta("1", "sales.order", "order-import", "Order Import", null),
                List.of(
                        new ExcelSheetPlan(
                                "Order",
                                "order",
                                true,
                                List.of(
                                        new ExcelColumnPlan("relateId", "关联标识"),
                                        new ExcelColumnPlan("orderNo", "Order No")
                                ),
                                List.of(List.of("R-1", "SO-1"))
                        ),
                        new ExcelSheetPlan(
                                "Order Line",
                                "orderLine",
                                false,
                                List.of(
                                        new ExcelColumnPlan("relateId", "关联标识"),
                                        new ExcelColumnPlan("sku", "SKU"),
                                        new ExcelColumnPlan("qty", "Qty")
                                ),
                                List.of(List.of("R-1", "SKU-1", 2))
                        )
                )
        );
        ParsedWorkbook parsedWorkbook = new ExcelWorkbookParser().parse(new ExcelWorkbookPlanWriter().writeToBytes(workbookPlan));
        DynamicImportPlan plan = new DynamicImportPlanBuilder().build(descriptor(), parsedWorkbook,
                new BuildDynamicImportPlanCommand("sales.order", "orderNo", ImportDuplicateStrategy.ERROR,
                        List.of(new BuildDynamicImportPlanCommand.ChildSheetCommand(
                                "orderLine", "sku", ImportDuplicateStrategy.ERROR
                        ))));

        GroupedWorkbook grouped = grouper.group(plan, parsedWorkbook);

        assertThat(grouped.errorRows()).isEmpty();
        assertThat(grouped.groups()).containsOnlyKeys("R-1");
        assertThat(grouped.groups().get("R-1").childRowsBySheetKey().get("Order Line")).hasSize(1);
    }

    private DynamicImportPlan plan(ImportDuplicateStrategy childStrategy) {
        return new DynamicImportPlan("sales.order", null, List.of(
                new DynamicImportPlan.SheetPlan(
                        "order",
                        "order",
                        "Order",
                        true,
                        "orderNo",
                        ImportDuplicateStrategy.ERROR,
                        List.of(
                                new DynamicImportPlan.FieldPlan("order", "relateId", "关联标识", true, false, false),
                                new DynamicImportPlan.FieldPlan("order", "orderNo", "Order No", FieldType.STRING,
                                        false, true, false)
                        )
                ),
                new DynamicImportPlan.SheetPlan(
                        "orderLine",
                        "orderLine",
                        "Order Line",
                        false,
                        "sku",
                        childStrategy,
                        List.of(
                                new DynamicImportPlan.FieldPlan("orderLine", "relateId", "关联标识", true, false, false),
                                new DynamicImportPlan.FieldPlan("orderLine", "sku", "SKU", FieldType.STRING,
                                        false, true, false),
                                new DynamicImportPlan.FieldPlan("orderLine", "qty", "Qty", FieldType.INTEGER,
                                        false, true, false)
                        )
                )
        ));
    }

    private DynamicImportPlan mainIntegerPlan() {
        return new DynamicImportPlan("sales.order", null, List.of(
                new DynamicImportPlan.SheetPlan(
                        "order",
                        "order",
                        "Order",
                        true,
                        "orderNo",
                        ImportDuplicateStrategy.ERROR,
                        List.of(
                                new DynamicImportPlan.FieldPlan("order", "relateId", "关联标识", true, false, false),
                                new DynamicImportPlan.FieldPlan("order", "orderNo", "Order No", FieldType.INTEGER,
                                        false, true, false)
                        )
                ),
                new DynamicImportPlan.SheetPlan(
                        "orderLine",
                        "orderLine",
                        "Order Line",
                        false,
                        "sku",
                        ImportDuplicateStrategy.ERROR,
                        List.of(
                                new DynamicImportPlan.FieldPlan("orderLine", "relateId", "关联标识", true, false, false),
                                new DynamicImportPlan.FieldPlan("orderLine", "sku", "SKU", FieldType.STRING,
                                        false, true, false),
                                new DynamicImportPlan.FieldPlan("orderLine", "qty", "Qty", FieldType.INTEGER,
                                        false, true, false)
                        )
                )
        ));
    }

    private ParsedWorkbook workbook(ParsedSheet main, ParsedSheet child) {
        return new ParsedWorkbook(new ExcelWorkbookMeta("1", "sales.order", null, null, null), List.of(main, child));
    }

    @SafeVarargs
    private ParsedSheet mainRows(List<String>... rows) {
        return new ParsedSheet("Order", "order", List.of(
                new ParsedColumn(0, null, ExcelExchangeProtocol.RELATE_ID_FIELD, ExcelExchangeProtocol.RELATE_ID_TITLE),
                new ParsedColumn(1, "order", "orderNo", "Order No")
        ), List.of(rows));
    }

    @SafeVarargs
    private ParsedSheet childRows(List<String>... rows) {
        return new ParsedSheet("Order Line", "orderLine", List.of(
                new ParsedColumn(0, null, ExcelExchangeProtocol.RELATE_ID_FIELD, ExcelExchangeProtocol.RELATE_ID_TITLE),
                new ParsedColumn(1, "orderLine", "sku", "SKU"),
                new ParsedColumn(2, "orderLine", "qty", "Qty")
        ), List.of(rows));
    }

    private DynamicModuleDescriptor descriptor() {
        return DynamicModuleDescriptor.from(ModuleDefinition.builder("sales.order", "Order")
                .entities(List.of(
                        new EntityDefinition("order", "sales_order", "Order", List.of(
                                FieldDefinition.string("orderNo", "Order No")
                        )),
                        new EntityDefinition("orderLine", "sales_order_line", "Order Line", List.of(
                                FieldDefinition.string("sku", "SKU"),
                                FieldDefinition.integer("qty", "Qty")
                        ))
                ))
                .relations(List.of(EntityRelationDefinition.child("lines", "order", "orderLine", "orderId")))
                .build());
    }
}
