package net.ximatai.muyun.spring.platform.exchange.importer;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
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

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

class DynamicImportErrorWorkbookBuilderTest {
    private final DynamicImportErrorWorkbookBuilder builder = new DynamicImportErrorWorkbookBuilder();

    @Test
    void shouldBuildMainAndChildErrorWorkbookWithStableColumnsAndRows() {
        ExcelWorkbookMeta meta = new ExcelWorkbookMeta("2", "sales.order", "custom-plan", "Custom Plan", "UTC");

        ExcelWorkbookPlan workbook = builder.build(plan(), List.of(
                errorRow("Order", rawValues(
                        "关联标识", "R-1",
                        "Order No", "SO-001",
                        "Placed At", "2026-06-08 09:30:00"
                ), "Order No already exists"),
                errorRow("Order Line", rawValues(
                        "关联标识", "R-1",
                        "SKU", "SKU-01",
                        "Qty", "bad"
                ), "Qty must be a number")
        ), meta);

        assertThat(workbook.meta()).isSameAs(meta);
        assertThat(workbook.sheets()).hasSize(3);

        ExcelSheetPlan order = workbook.sheets().get(0);
        assertThat(order.sheetName()).isEqualTo("Order");
        assertThat(order.entityAlias()).isEqualTo("order");
        assertThat(order.main()).isTrue();
        assertThat(order.columns())
                .extracting(ExcelColumnPlan::fieldName, ExcelColumnPlan::title)
                .containsExactly(
                        tuple("relateId", "关联标识"),
                        tuple("orderNo", "Order No"),
                        tuple("placedAt", "Placed At"),
                        tuple(DynamicImportErrorWorkbookBuilder.ERROR_FIELD,
                                DynamicImportErrorWorkbookBuilder.ERROR_TITLE)
                );
        assertThat(order.rows()).containsExactly(List.of(
                "R-1",
                "SO-001",
                "2026-06-08 09:30:00",
                "Order No already exists"
        ));

        ExcelSheetPlan line = workbook.sheets().get(1);
        assertThat(line.sheetName()).isEqualTo("Order Line");
        assertThat(line.entityAlias()).isEqualTo("orderLine");
        assertThat(line.main()).isFalse();
        assertThat(line.columns())
                .extracting(ExcelColumnPlan::fieldName)
                .containsExactly("relateId", "sku", "qty", "errorReason");
        assertThat(line.rows()).containsExactly(List.of("R-1", "SKU-01", "bad", "Qty must be a number"));

        ExcelSheetPlan emptyChild = workbook.sheets().get(2);
        assertThat(emptyChild.sheetName()).isEqualTo("Shipment");
        assertThat(emptyChild.rows()).isEmpty();
        assertThat(emptyChild.columns())
                .extracting(ExcelColumnPlan::fieldName)
                .containsExactly("relateId", "carrier", "errorReason");
    }

    @Test
    void shouldNotDuplicateRelateIdWhenPlanAlreadyContainsIt() {
        ExcelSheetPlan sheet = builder.build(plan(), null).sheets().getFirst();

        assertThat(sheet.columns())
                .extracting(ExcelColumnPlan::fieldName)
                .containsExactly("relateId", "orderNo", "placedAt", "errorReason");
        assertThat(sheet.columns())
                .filteredOn(column -> ExcelExchangeProtocol.RELATE_ID_FIELD.equals(column.fieldName()))
                .hasSize(1);
    }

    @Test
    void shouldBuildDefaultMetaWhenNotProvided() {
        ExcelWorkbookPlan workbook = builder.build(plan(), List.of());

        assertThat(workbook.meta()).isEqualTo(new ExcelWorkbookMeta(
                ExcelExchangeProtocol.PROTOCOL_VERSION,
                "sales.order",
                "order-import",
                null,
                null
        ));
    }

    @Test
    void shouldRejectUnknownErrorRowSheetKey() {
        assertThatThrownBy(() -> builder.build(plan(), List.of(
                errorRow("Unknown", rawValues("Order No", "SO-001"), "bad sheet")
        )))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("sheetKey does not belong to plan")
                .hasMessageContaining("Unknown");
    }

    @Test
    void shouldRejectBusinessFieldConflictingWithErrorColumn() {
        DynamicImportPlan plan = new DynamicImportPlan(
                "sales.order",
                "order-import",
                List.of(new DynamicImportPlan.SheetPlan(
                        "Order",
                        "order",
                        "Order",
                        true,
                        "orderNo",
                        ImportDuplicateStrategy.ERROR,
                        List.of(
                                relateIdField("order"),
                                field("order", "orderNo", "Order No", FieldType.STRING),
                                field("order", DynamicImportErrorWorkbookBuilder.ERROR_FIELD, "Business Error Reason", FieldType.STRING)
                        )
                ))
        );

        assertThatThrownBy(() -> builder.build(plan, List.of()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("field conflicts with error column")
                .hasMessageContaining("Order.errorReason");
    }

    @Test
    void shouldWriteAndParseErrorWorkbookWithErrorColumn() {
        ExcelWorkbookPlan workbook = builder.build(plan(), List.of(
                errorRow("Order", rawValues(
                        "关联标识", "R-1",
                        "Order No", "SO-001",
                        "Placed At", "2026-06-08 09:30:00"
                ), "Order No already exists")
        ));

        byte[] bytes = new ExcelWorkbookPlanWriter().writeToBytes(workbook);

        ParsedWorkbook parsed = new ExcelWorkbookParser().parse(bytes);
        assertThat(parsed.meta()).isEqualTo(workbook.meta());

        ParsedSheet order = parsed.sheets().getFirst();
        assertThat(order.columns())
                .extracting(ParsedColumn::fieldName, ParsedColumn::title)
                .containsExactly(
                        tuple("relateId", "关联标识"),
                        tuple("orderNo", "Order No"),
                        tuple("placedAt", "Placed At"),
                        tuple("errorReason", "错误原因")
                );
        assertThat(order.columns().get(3).entityAlias()).isEqualTo("order");
        assertThat(order.rows()).containsExactly(List.of(
                "R-1",
                "SO-001",
                "2026-06-08 09:30:00",
                "Order No already exists"
        ));

        ParsedSheet line = parsed.sheets().get(1);
        assertThat(line.rows()).isEmpty();
        assertThat(line.columns())
                .extracting(ParsedColumn::fieldName)
                .containsExactly("relateId", "sku", "qty", "errorReason");
    }

    @Test
    void shouldPreserveOnlyAffectedWholeGroupsForCorrectedWorkbookRetry() {
        DynamicImportPlan plan = plan();
        List<ExcelSheetPlan> sourceSheets = List.of(
                new ExcelSheetPlan("Order", "order", true,
                        List.of(new ExcelColumnPlan("relateId", "关联标识"), new ExcelColumnPlan("orderNo", "Order No"),
                                new ExcelColumnPlan("placedAt", "Placed At")),
                        List.of(List.of("R-1", "SO-1", "2026-06-08T01:30:00Z"), List.of("R-2", "SO-2", "2026-06-08T01:30:00Z"))),
                new ExcelSheetPlan("Order Line", "orderLine", false,
                        List.of(new ExcelColumnPlan("relateId", "关联标识"), new ExcelColumnPlan("sku", "SKU"), new ExcelColumnPlan("qty", "Qty")),
                        List.of(List.of("R-1", "SKU-ok", "1"), List.of("R-1", "SKU-bad", "bad"), List.of("R-2", "SKU-other", "2"))),
                new ExcelSheetPlan("Shipment", "shipment", false,
                        List.of(new ExcelColumnPlan("relateId", "关联标识"), new ExcelColumnPlan("carrier", "Carrier")),
                        List.of(List.of("R-1", ""), List.of("R-2", ""))));
        ExcelWorkbookParser parser = new ExcelWorkbookParser();
        ExcelWorkbookPlanWriter writer = new ExcelWorkbookPlanWriter();
        ParsedWorkbook source = parser.parse(writer.writeToBytes(new ExcelWorkbookPlan(sourceSheets)));
        var retry = builder.buildRetryWorkbook(plan, List.of(errorRow("Order Line",
                rawValues("关联标识", "R-1", "SKU", "SKU-bad", "Qty", "bad"), "Qty must be a number")), source);
        assertThat(retry.sheets().get(0).rows()).hasSize(1);
        assertThat(retry.sheets().get(1).rows()).hasSize(2);
        assertThat(retry.sheets().get(2).rows()).hasSize(1);
        assertThat(retry.sheets().get(1).rows().get(1).getLast()).isEqualTo("Qty must be a number");
        // Correct the value in the actual error workbook, retaining the diagnostic column.
        List<ExcelSheetPlan> corrected = retry.sheets().stream().map(sheet -> {
            List<List<Object>> rows = sheet.rows().stream().map(row -> {
                var copy = new java.util.ArrayList<>(row);
                if (sheet.entityAlias().equals("orderLine") && copy.get(1).equals("SKU-bad")) copy.set(2, "3");
                return (List<Object>) copy;
            }).toList();
            return new ExcelSheetPlan(sheet.sheetName(), sheet.entityAlias(), sheet.main(), sheet.columns(), rows);
        }).toList();
        ParsedWorkbook reparsed = parser.parse(writer.writeToBytes(new ExcelWorkbookPlan(corrected)));
        // The execution plan intentionally omits the diagnostic column; coverage-only rows remain coverage-only.
        GroupedWorkbook grouped = new ImportWorkbookGrouper().group(plan, reparsed);
        assertThat(grouped.errorRows()).isEmpty();
        assertThat(grouped.groups()).containsOnlyKeys("R-1");
        assertThat(grouped.groups().get("R-1").childRowsBySheetKey().get("Order Line"))
                .extracting(row -> row.convertedValues().get("qty")).containsExactly(1, 3);
        assertThat(grouped.groups().get("R-1").isChildSheetCovered("Shipment")).isTrue();
    }

    private DynamicImportPlan plan() {
        return new DynamicImportPlan(
                "sales.order",
                "order-import",
                List.of(
                        new DynamicImportPlan.SheetPlan(
                                "Order",
                                "order",
                                "Order",
                                true,
                                "orderNo",
                                ImportDuplicateStrategy.ERROR,
                                List.of(
                                        relateIdField("order"),
                                        field("order", "orderNo", "Order No", FieldType.STRING),
                                        field("order", "placedAt", "Placed At", FieldType.TIMESTAMP)
                                )
                        ),
                        new DynamicImportPlan.SheetPlan(
                                "Order Line",
                                "orderLine",
                                "Order Line",
                                false,
                                "sku",
                                ImportDuplicateStrategy.ERROR,
                                List.of(
                                        relateIdField("orderLine"),
                                        field("orderLine", "sku", "SKU", FieldType.STRING),
                                        field("orderLine", "qty", "Qty", FieldType.INTEGER)
                                )
                        ),
                        new DynamicImportPlan.SheetPlan(
                                "Shipment",
                                "shipment",
                                "Shipment",
                                false,
                                "carrier",
                                ImportDuplicateStrategy.ERROR,
                                List.of(
                                        relateIdField("shipment"),
                                        field("shipment", "carrier", "Carrier", FieldType.STRING)
                                )
                        )
                )
        );
    }

    private DynamicImportPlan.FieldPlan relateIdField(String entityAlias) {
        return new DynamicImportPlan.FieldPlan(
                entityAlias,
                ExcelExchangeProtocol.RELATE_ID_FIELD,
                ExcelExchangeProtocol.RELATE_ID_TITLE,
                FieldType.TEXT,
                true,
                false,
                false
        );
    }

    private DynamicImportPlan.FieldPlan field(String entityAlias, String fieldName, String title, FieldType fieldType) {
        return new DynamicImportPlan.FieldPlan(entityAlias, fieldName, title, fieldType, false, true, false);
    }

    private ImportErrorRow errorRow(String sheetKey, LinkedHashMap<String, String> rawValues, String message) {
        return new ImportErrorRow(sheetKey, rawValues, message, "R-1");
    }

    private LinkedHashMap<String, String> rawValues(String... values) {
        LinkedHashMap<String, String> rawValues = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            rawValues.put(values[index], values[index + 1]);
        }
        return rawValues;
    }
}
