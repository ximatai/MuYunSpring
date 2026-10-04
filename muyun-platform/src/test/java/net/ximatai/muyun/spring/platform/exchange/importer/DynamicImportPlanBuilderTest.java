package net.ximatai.muyun.spring.platform.exchange.importer;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookMeta;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedColumn;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedSheet;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedWorkbook;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicImportPlanBuilderTest {
    private final DynamicImportPlanBuilder builder = new DynamicImportPlanBuilder();

    @Test
    void shouldBuildMainAndChildPlanFromParsedWorkbook() {
        DynamicImportPlan plan = builder.build(descriptor(), workbook(
                new ParsedSheet("Order", "order", columns("order", "orderNo", "placedAt", "placedAtTimeZone"), List.of(
                        List.of("R-1", "SO-001", "2026-06-08 09:30:00", "Asia/Shanghai")
                )),
                new ParsedSheet("Order Line", "orderLine", columns("orderLine", "sku", "qty"), List.of(
                        List.of("R-1", "SKU-1", "2")
                ))
        ), command(List.of(childCommand("orderLine", "sku", ImportDuplicateStrategy.ERROR))));

        assertThat(plan.moduleAlias()).isEqualTo("sales.order");
        assertThat(plan.planSource()).isEqualTo("order-import");
        assertThat(plan.sheets()).hasSize(2);
        DynamicImportPlan.SheetPlan main = plan.sheets().getFirst();
        assertThat(main.sheetKey()).isEqualTo("Order");
        assertThat(main.entityAlias()).isEqualTo("order");
        assertThat(main.main()).isTrue();
        assertThat(main.matchFieldName()).isEqualTo("orderNo");
        assertThat(main.fields()).extracting(DynamicImportPlan.FieldPlan::fieldName)
                .containsExactly("relateId", "orderNo", "placedAt", "placedAtTimeZone");
        assertThat(main.fields().getFirst().relateId()).isTrue();
        assertThat(main.fields().getFirst().fieldType()).isEqualTo(FieldType.TEXT);
        assertThat(main.fields().get(1).fieldType()).isEqualTo(FieldType.STRING);
        assertThat(main.fields().get(2).fieldType()).isEqualTo(FieldType.ZONED_TIMESTAMP);
        assertThat(main.fields().get(3).fieldType()).isEqualTo(FieldType.STRING);
        assertThat(main.fields().get(2).matchKeyCandidate()).isTrue();
        assertThat(main.fields().get(3).companion()).isTrue();
        assertThat(main.fields().get(3).matchKeyCandidate()).isFalse();

        DynamicImportPlan.SheetPlan child = plan.sheets().get(1);
        assertThat(child.sheetKey()).isEqualTo("Order Line");
        assertThat(child.entityAlias()).isEqualTo("orderLine");
        assertThat(child.duplicateStrategy()).isEqualTo(ImportDuplicateStrategy.ERROR);
        assertThat(child.fields().get(2).fieldType()).isEqualTo(FieldType.INTEGER);
    }

    @Test
    void shouldReadDiagnosticColumnsWithoutMakingThemWritableOrMatchable() {
        ParsedWorkbook parsed = workbook(new ParsedSheet("Order", "order",
                columns("order", "orderNo", "errorReason"), List.of(List.of("R-1", "SO-1", "旧错误"))));
        DynamicImportPlan plan = builder.build(descriptor(), parsed, command(List.of()));
        assertThat(plan.mainSheet().fields()).extracting(DynamicImportPlan.FieldPlan::fieldName)
                .containsExactly("relateId", "orderNo");
        assertThat(new ImportWorkbookGrouper().group(plan, parsed).groups().get("R-1")
                .mainRow().convertedValues()).containsOnlyKeys("orderNo");
        assertThatThrownBy(() -> builder.build(descriptor(), parsed,
                new BuildDynamicImportPlanCommand("sales.order", "errorReason", ImportDuplicateStrategy.SKIP, List.of())))
                .hasMessageContaining("business field");
    }

    @Test
    void shouldRejectUnknownSheet() {
        assertThatThrownBy(() -> builder.build(descriptor(), workbook(
                sheet("Order", "order", "orderNo"),
                sheet("Unknown", "other", "code")
        ), command(List.of())))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void shouldRejectFieldOutsideEntity() {
        assertThatThrownBy(() -> builder.build(descriptor(), workbook(
                new ParsedSheet("Order", "order", columns("order", "orderNo", "unknown"),
                        List.of(List.of("R-1", "SO-1", "V-1")))
        ), command(List.of())))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not belong to entity");
    }

    @Test
    void shouldRejectInvalidMatchField() {
        ParsedWorkbook workbook = workbook(sheet("Order", "order", "orderNo"));

        assertThatThrownBy(() -> builder.build(descriptor(), workbook,
                new BuildDynamicImportPlanCommand("sales.order", "missing", ImportDuplicateStrategy.ERROR, List.of())))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("match field not found");

        assertThatThrownBy(() -> builder.build(descriptor(), workbook,
                new BuildDynamicImportPlanCommand("sales.order", "relateId", ImportDuplicateStrategy.ERROR, List.of())))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("business field");

        ParsedWorkbook companionWorkbook = workbook(new ParsedSheet(
                "Order",
                "order",
                columns("order", "orderNo", "placedAt", "placedAtTimeZone"),
                List.of(List.of("R-1", "SO-1", "2026-06-08 09:30:00", "Asia/Shanghai"))
        ));
        assertThatThrownBy(() -> builder.build(descriptor(), companionWorkbook,
                new BuildDynamicImportPlanCommand("sales.order", "placedAtTimeZone", ImportDuplicateStrategy.ERROR, List.of())))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("business field");
    }

    @Test
    void shouldRejectChildCommandProblems() {
        ParsedWorkbook workbook = workbook(
                sheet("Order", "order", "orderNo"),
                new ParsedSheet("Order Line", "orderLine", columns("orderLine", "sku"), List.of(List.of("R-1", "SKU-1")))
        );

        assertThatThrownBy(() -> builder.build(descriptor(), workbook, command(List.of())))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("strategy required");

        assertThatThrownBy(() -> builder.build(descriptor(), workbook,
                command(List.of(childCommand("notChild", "code", ImportDuplicateStrategy.ERROR)))))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not first-level child");

        assertThatThrownBy(() -> builder.build(descriptor(), workbook(sheet("Order", "order", "orderNo")),
                command(List.of(childCommand("orderLine", "sku", ImportDuplicateStrategy.ERROR)))))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("no parsed sheet");
    }

    @Test
    void shouldRejectModuleAliasMismatch() {
        ParsedWorkbook workbook = workbook(sheet("Order", "order", "orderNo"));

        assertThatThrownBy(() -> builder.build(descriptor(), workbook,
                new BuildDynamicImportPlanCommand("sales.contract", "orderNo", ImportDuplicateStrategy.ERROR, List.of())))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("moduleAlias mismatch");

        ParsedWorkbook metaMismatch = new ParsedWorkbook(
                new ExcelWorkbookMeta("1", "sales.contract", null, null, null),
                List.of(sheet("Order", "order", "orderNo"))
        );
        assertThatThrownBy(() -> builder.build(descriptor(), metaMismatch, command(List.of())))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("workbook moduleAlias mismatch");
    }

    private DynamicModuleDescriptor descriptor() {
        return DynamicModuleDescriptor.from(ModuleDefinition.builder("sales.order", "Order")
                .entities(List.of(
                        new EntityDefinition("order", "sales_order", "Order", List.of(
                                FieldDefinition.string("orderNo", "Order No"),
                                FieldDefinition.zonedTimestamp("placedAt", "Placed At"),
                                FieldDefinition.zonedTimestampTimeZone("placedAt", "placed_at")
                        )),
                        new EntityDefinition("orderLine", "sales_order_line", "Order Line", List.of(
                                FieldDefinition.string("sku", "SKU"),
                                FieldDefinition.integer("qty", "Qty")
                        )),
                        new EntityDefinition("lineBatch", "sales_line_batch", "Line Batch", List.of(
                                FieldDefinition.string("batchNo", "Batch No")
                        ))
                ))
                .relations(List.of(
                        EntityRelationDefinition.child("lines", "order", "orderLine", "orderId"),
                        EntityRelationDefinition.child("batches", "orderLine", "lineBatch", "lineId")
                ))
                .build());
    }

    private ParsedWorkbook workbook(ParsedSheet... sheets) {
        return new ParsedWorkbook(
                new ExcelWorkbookMeta("1", "sales.order", "order-import", "Order Import", null),
                List.of(sheets)
        );
    }

    private BuildDynamicImportPlanCommand command(List<BuildDynamicImportPlanCommand.ChildSheetCommand> children) {
        return new BuildDynamicImportPlanCommand("sales.order", "orderNo", ImportDuplicateStrategy.ERROR, children);
    }

    private BuildDynamicImportPlanCommand.ChildSheetCommand childCommand(String entityAlias,
                                                                         String matchFieldName,
                                                                         ImportDuplicateStrategy strategy) {
        return new BuildDynamicImportPlanCommand.ChildSheetCommand(entityAlias, matchFieldName, strategy);
    }

    private ParsedSheet sheet(String sheetName, String entityAlias, String fieldName) {
        return new ParsedSheet(sheetName, entityAlias, columns(entityAlias, fieldName), List.of(List.of("R-1", "V-1")));
    }

    private List<ParsedColumn> columns(String entityAlias, String... fieldNames) {
        java.util.ArrayList<ParsedColumn> columns = new java.util.ArrayList<>();
        columns.add(new ParsedColumn(0, null, ExcelExchangeProtocol.RELATE_ID_FIELD, ExcelExchangeProtocol.RELATE_ID_TITLE));
        for (int index = 0; index < fieldNames.length; index++) {
            columns.add(new ParsedColumn(index + 1, entityAlias, fieldNames[index], fieldNames[index]));
        }
        return columns;
    }
}
