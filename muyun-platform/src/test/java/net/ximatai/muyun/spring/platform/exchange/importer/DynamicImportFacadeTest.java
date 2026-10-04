package net.ximatai.muyun.spring.platform.exchange.importer;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookMeta;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelColumnPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelSheetPlan;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedColumn;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedSheet;
import net.ximatai.muyun.spring.platform.exchange.model.ParsedWorkbook;
import net.ximatai.muyun.spring.platform.exchange.protocol.ExcelExchangeProtocol;
import net.ximatai.muyun.spring.platform.exchange.reader.ExcelWorkbookParser;
import net.ximatai.muyun.spring.platform.exchange.writer.ExcelWorkbookPlanWriter;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.mock;

class DynamicImportFacadeTest {
    private static final String MODULE = "sales.order";

    @Test
    void shouldExposeTransactionalServiceFacade() throws NoSuchMethodException {
        assertThat(DynamicImportFacade.class).hasAnnotation(Service.class);
        Method method = DynamicImportFacade.class.getMethod("importWorkbook", DynamicImportCommand.class);
        assertThat(method.isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void shouldParseWorkbookIntoImportParseResult() {
        byte[] bytes = new ExcelWorkbookPlanWriter().writeToBytes(new ExcelWorkbookPlan(meta(), List.of(
                new ExcelSheetPlan("Order", "order", true, List.of(
                        new ExcelColumnPlan("relateId", "关联标识"),
                        new ExcelColumnPlan("orderNo", "Order No")
                ), List.of(List.of("R-1", "SO-1"))),
                new ExcelSheetPlan("Order Line", "orderLine", false, List.of(
                        new ExcelColumnPlan("relateId", "关联标识"),
                        new ExcelColumnPlan("sku", "SKU")
                ), List.of(List.of("R-1", "SKU-1")))
        )));
        DynamicImportFacade facade = new DynamicImportFacade(mock(DynamicRecordService.class));

        DynamicImportParseResult result = facade.parse(orderDescriptor(), bytes);

        assertThat(result.moduleAlias()).isEqualTo(MODULE);
        assertThat(result.mainEntityAlias()).isEqualTo("order");
        assertThat(result.mainSheetName()).isEqualTo("Order");
        assertThat(result.sheets()).hasSize(2);
        assertThat(result.sheets().getFirst())
                .satisfies(sheet -> {
                    assertThat(sheet.sheetKey()).isEqualTo("Order");
                    assertThat(sheet.sheetName()).isEqualTo("Order");
                    assertThat(sheet.entityAlias()).isEqualTo("order");
                    assertThat(sheet.main()).isTrue();
                    assertThat(sheet.rowCount()).isEqualTo(1);
                    assertThat(sheet.fields())
                            .extracting(DynamicImportParseResult.Field::fieldName,
                                    DynamicImportParseResult.Field::relateId,
                                    DynamicImportParseResult.Field::matchKeyCandidate)
                            .containsExactly(
                                    tuple("relateId", true, false),
                                    tuple("orderNo", false, true)
                            );
                });
        assertThat(result.sheets().get(1))
                .satisfies(sheet -> {
                    assertThat(sheet.entityAlias()).isEqualTo("orderLine");
                    assertThat(sheet.main()).isFalse();
                    assertThat(sheet.rowCount()).isEqualTo(1);
                });
    }

    @Test
    void shouldStoreErrorFilePayloadByTokenDefensively() {
        DynamicImportErrorFileService service = new DynamicImportErrorFileService(
                Clock.fixed(Instant.parse("2026-06-09T00:00:00Z"), ZoneOffset.UTC));
        byte[] bytes = new byte[]{1, 2, 3};

        String token = service.save(MODULE, "tenant_a", "errors.xlsx", bytes);
        bytes[0] = 9;

        DynamicImportErrorFileService.ErrorFilePayload payload = service.get(MODULE, "tenant_a", token);
        assertThat(payload.fileName()).isEqualTo("errors.xlsx");
        assertThat(payload.content()).containsExactly(1, 2, 3);

        byte[] read = payload.content();
        read[0] = 8;
        assertThat(payload.content()).containsExactly(1, 2, 3);
        assertThat(service.get("crm.customer", "tenant_a", token)).isNull();
        assertThat(service.get(MODULE, "tenant_b", token)).isNull();
    }

    @Test
    void shouldImportWorkbookWithoutErrorWorkbookWhenExecutionHasNoErrors() {
        List<String> calls = new ArrayList<>();
        ParsedWorkbook workbook = workbook(meta(), mainSheet(List.of(List.of("R-1", "SO-1"))));
        DynamicImportPlan plan = plan();
        GroupedWorkbook groupedWorkbook = new GroupedWorkbook(new LinkedHashMap<>(), List.of());
        DynamicImportExecutionResult executionResult =
                new DynamicImportExecutionResult(1, 0, 0, List.of(), Map.of());

        RecordingParser parser = new RecordingParser(calls, workbook);
        RecordingPlanBuilder planBuilder = new RecordingPlanBuilder(calls, plan);
        RecordingGrouper grouper = new RecordingGrouper(calls, groupedWorkbook);
        RecordingExecutor executor = new RecordingExecutor(calls, executionResult);
        RecordingErrorWorkbookBuilder errorWorkbookBuilder = new RecordingErrorWorkbookBuilder(calls);
        RecordingWorkbookWriter writer = new RecordingWorkbookWriter(calls, new byte[]{9});
        DynamicImportFacade facade = new DynamicImportFacade(
                parser, planBuilder, grouper, executor, errorWorkbookBuilder, writer);

        DynamicImportCommand command = command(new byte[]{1, 2, 3});
        DynamicImportResult result = facade.importWorkbook(command);

        assertThat(calls).containsExactly("parse", "buildPlan", "group", "execute");
        assertThat(parser.bytes).containsExactly(1, 2, 3);
        assertThat(planBuilder.descriptor).isSameAs(command.descriptor());
        assertThat(planBuilder.workbook).isSameAs(workbook);
        assertThat(planBuilder.command).isSameAs(command.buildPlanCommand());
        assertThat(grouper.plan).isSameAs(plan);
        assertThat(grouper.workbook).isSameAs(workbook);
        assertThat(executor.command.plan()).isSameAs(plan);
        assertThat(executor.command.workbook()).isSameAs(groupedWorkbook);
        assertThat(errorWorkbookBuilder.called).isFalse();
        assertThat(writer.called).isFalse();
        assertThat(result.plan()).isSameAs(plan);
        assertThat(result.groupedWorkbook()).isSameAs(groupedWorkbook);
        assertThat(result.executionResult()).isSameAs(executionResult);
        assertThat(result.errorWorkbookBytes()).isNull();
    }

    @Test
    void shouldBuildErrorWorkbookWithParsedWorkbookMetaWhenErrorsExist() {
        ExcelWorkbookMeta meta = new ExcelWorkbookMeta(
                ExcelExchangeProtocol.PROTOCOL_VERSION,
                MODULE,
                "order-import",
                "Order Import",
                "Asia/Shanghai"
        );
        ParsedWorkbook workbook = workbook(meta, mainSheet(List.of(List.of("R-1", "SO-1"))));
        DynamicImportPlan plan = plan();
        GroupedWorkbook groupedWorkbook = new GroupedWorkbook(new LinkedHashMap<>(), List.of());
        DynamicImportExecutionResult executionResult = new DynamicImportExecutionResult(
                0,
                0,
                0,
                List.of(errorRow("R-1", "SO-1", "导入匹配到已有记录: orderNo")),
                Map.of()
        );
        DynamicImportFacade facade = new DynamicImportFacade(
                new RecordingParser(new ArrayList<>(), workbook),
                new RecordingPlanBuilder(new ArrayList<>(), plan),
                new RecordingGrouper(new ArrayList<>(), groupedWorkbook),
                new RecordingExecutor(new ArrayList<>(), executionResult),
                new DynamicImportErrorWorkbookBuilder(),
                new ExcelWorkbookPlanWriter());

        DynamicImportResult result = facade.importWorkbook(command(new byte[]{1}));

        assertThat(result.errorWorkbookBytes()).isNotEmpty();
        ParsedWorkbook parsedErrorWorkbook = new ExcelWorkbookParser().parse(result.errorWorkbookBytes());
        assertThat(parsedErrorWorkbook.meta()).isEqualTo(meta);
        ParsedSheet order = parsedErrorWorkbook.sheets().getFirst();
        assertThat(order.columns())
                .extracting(ParsedColumn::fieldName, ParsedColumn::title)
                .containsExactly(
                        tuple("relateId", "关联标识"),
                        tuple("orderNo", "Order No"),
                        tuple("errorReason", "错误原因")
                );
        assertThat(order.rows()).containsExactly(List.of(
                "R-1",
                "SO-1",
                "导入匹配到已有记录: orderNo"
        ));
    }

    @Test
    void shouldValidateCommandInputs() {
        DynamicModuleDescriptor descriptor = descriptor();
        BuildDynamicImportPlanCommand buildCommand = buildCommand();

        assertThatThrownBy(() -> new DynamicImportCommand(null, new byte[]{1}, buildCommand))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("module descriptor");
        assertThatThrownBy(() -> new DynamicImportCommand(descriptor, new byte[0], buildCommand))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("excel bytes");
        assertThatThrownBy(() -> new DynamicImportCommand(descriptor, new byte[]{1}, null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("build plan command");

        DynamicImportFacade facade = new DynamicImportFacade(
                new RecordingParser(new ArrayList<>(), workbook(meta(), mainSheet(List.of(List.of("R-1", "SO-1"))))),
                new RecordingPlanBuilder(new ArrayList<>(), plan()),
                new RecordingGrouper(new ArrayList<>(), new GroupedWorkbook(new LinkedHashMap<>(), List.of())),
                new RecordingExecutor(new ArrayList<>(), new DynamicImportExecutionResult(0, 0, 0, List.of(), Map.of())),
                new DynamicImportErrorWorkbookBuilder(),
                new ExcelWorkbookPlanWriter());
        assertThatThrownBy(() -> facade.importWorkbook(null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("dynamic import command must not be null");
    }

    private DynamicImportCommand command(byte[] bytes) {
        return new DynamicImportCommand(descriptor(), bytes, buildCommand());
    }

    private BuildDynamicImportPlanCommand buildCommand() {
        return new BuildDynamicImportPlanCommand(MODULE, "orderNo", ImportDuplicateStrategy.ERROR, List.of());
    }

    private DynamicModuleDescriptor descriptor() {
        return DynamicModuleDescriptor.from(new ModuleDefinition(
                MODULE,
                "Order",
                List.of(new EntityDefinition("order", "sales_order", "Order", List.of(
                        FieldDefinition.string("orderNo", "Order No")
                )))
        ));
    }

    private DynamicModuleDescriptor orderDescriptor() {
        return DynamicModuleDescriptor.from(ModuleDefinition.builder(MODULE, "Order")
                .entities(List.of(
                        new EntityDefinition("order", "sales_order", "Order", List.of(
                                FieldDefinition.string("orderNo", "Order No")
                        )),
                        new EntityDefinition("orderLine", "sales_order_line", "Order Line", List.of(
                                FieldDefinition.string("sku", "SKU")
                        ))
                ))
                .relations(List.of(net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition.child(
                        "lines", "order", "orderLine", "orderId")))
                .build());
    }

    private DynamicImportPlan plan() {
        return new DynamicImportPlan(MODULE, "order-import", List.of(
                new DynamicImportPlan.SheetPlan(
                        "order",
                        "order",
                        "Order",
                        true,
                        "orderNo",
                        ImportDuplicateStrategy.ERROR,
                        List.of(
                                new DynamicImportPlan.FieldPlan("order", "relateId", "关联标识",
                                        FieldType.TEXT, true, false, false),
                                new DynamicImportPlan.FieldPlan("order", "orderNo", "Order No",
                                        FieldType.STRING, false, true, false)
                        )
                )
        ));
    }

    private ParsedWorkbook workbook(ExcelWorkbookMeta meta, ParsedSheet sheet) {
        return new ParsedWorkbook(meta, List.of(sheet));
    }

    private ExcelWorkbookMeta meta() {
        return new ExcelWorkbookMeta(ExcelExchangeProtocol.PROTOCOL_VERSION, MODULE, null, null, null);
    }

    private ParsedSheet mainSheet(List<List<String>> rows) {
        return new ParsedSheet("Order", "order", List.of(
                new ParsedColumn(0, null, ExcelExchangeProtocol.RELATE_ID_FIELD, ExcelExchangeProtocol.RELATE_ID_TITLE),
                new ParsedColumn(1, "order", "orderNo", "Order No")
        ), rows);
    }

    private ImportErrorRow errorRow(String relateId, String orderNo, String message) {
        LinkedHashMap<String, String> rawValuesByFieldName = new LinkedHashMap<>();
        rawValuesByFieldName.put("relateId", relateId);
        rawValuesByFieldName.put("orderNo", orderNo);
        return new ImportErrorRow("order", rawValuesByFieldName, message, relateId);
    }

    private static class RecordingParser extends ExcelWorkbookParser {
        private final List<String> calls;
        private final ParsedWorkbook result;
        private byte[] bytes;

        RecordingParser(List<String> calls, ParsedWorkbook result) {
            this.calls = calls;
            this.result = result;
        }

        @Override
        public ParsedWorkbook parse(byte[] bytes) {
            calls.add("parse");
            this.bytes = bytes.clone();
            return result;
        }
    }

    private static class RecordingPlanBuilder extends DynamicImportPlanBuilder {
        private final List<String> calls;
        private final DynamicImportPlan result;
        private DynamicModuleDescriptor descriptor;
        private ParsedWorkbook workbook;
        private BuildDynamicImportPlanCommand command;

        RecordingPlanBuilder(List<String> calls, DynamicImportPlan result) {
            this.calls = calls;
            this.result = result;
        }

        @Override
        public DynamicImportPlan build(DynamicModuleDescriptor descriptor,
                                       ParsedWorkbook workbook,
                                       BuildDynamicImportPlanCommand command) {
            calls.add("buildPlan");
            this.descriptor = descriptor;
            this.workbook = workbook;
            this.command = command;
            return result;
        }
    }

    private static class RecordingGrouper extends ImportWorkbookGrouper {
        private final List<String> calls;
        private final GroupedWorkbook result;
        private DynamicImportPlan plan;
        private ParsedWorkbook workbook;

        RecordingGrouper(List<String> calls, GroupedWorkbook result) {
            this.calls = calls;
            this.result = result;
        }

        @Override
        public GroupedWorkbook group(DynamicImportPlan plan, ParsedWorkbook workbook) {
            calls.add("group");
            this.plan = plan;
            this.workbook = workbook;
            return result;
        }
    }

    private static class RecordingExecutor extends DynamicImportExecutor {
        private final List<String> calls;
        private final DynamicImportExecutionResult result;
        private ExecuteDynamicImportCommand command;

        RecordingExecutor(List<String> calls, DynamicImportExecutionResult result) {
            super(mock(DynamicRecordService.class));
            this.calls = calls;
            this.result = result;
        }

        @Override
        public DynamicImportExecutionResult execute(ExecuteDynamicImportCommand command) {
            calls.add("execute");
            this.command = command;
            return result;
        }
    }

    private static class RecordingErrorWorkbookBuilder extends DynamicImportErrorWorkbookBuilder {
        private final List<String> calls;
        private boolean called;

        RecordingErrorWorkbookBuilder(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public ExcelWorkbookPlan build(DynamicImportPlan plan,
                                       List<ImportErrorRow> errorRows,
                                       ExcelWorkbookMeta meta) {
            calls.add("buildErrorWorkbook");
            called = true;
            return super.build(plan, errorRows, meta);
        }
    }

    private static class RecordingWorkbookWriter extends ExcelWorkbookPlanWriter {
        private final List<String> calls;
        private final byte[] result;
        private boolean called;

        RecordingWorkbookWriter(List<String> calls, byte[] result) {
            this.calls = calls;
            this.result = result;
        }

        @Override
        public byte[] writeToBytes(ExcelWorkbookPlan plan) {
            calls.add("writeErrorWorkbook");
            called = true;
            return result.clone();
        }
    }
}
