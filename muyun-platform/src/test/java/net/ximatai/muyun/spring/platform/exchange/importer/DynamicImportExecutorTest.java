package net.ximatai.muyun.spring.platform.exchange.importer;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeReport;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceMatchMode;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceResolveItem;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceResolveMode;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceResolveRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceResolveResponse;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceResolveResult;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceResolveStatus;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicFormulaException;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordActionGateway;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicImportExecutorTest {
    private static final String MODULE = "sales.order";

    private final DynamicRecordService recordService = mock(DynamicRecordService.class);
    private final DynamicRecordActionGateway records = mock(DynamicRecordActionGateway.class);
    private final DynamicImportExecutor executor = new DynamicImportExecutor(recordService);

    @BeforeEach
    void setUp() {
        when(recordService.relations(MODULE)).thenReturn(List.of(
                new DynamicRelationDescriptor("lines", "order", "orderLine", "orderId", true, true)
        ));
        when(recordService.recordsForAction(MODULE, PlatformAction.IMPORT, "dynamic-import")).thenReturn(records);
        when(records.newRecord("order")).thenAnswer(ignored -> new DynamicRecord(orderEntity()));
        when(records.newRecord("orderLine")).thenAnswer(ignored -> new DynamicRecord(lineEntity()));
    }

    @Test
    void shouldCreateMainRecordWhenNoExistingMatch() {
        when(records.list(eq("order"), any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of());
        when(records.create(eq("order"), any(DynamicRecord.class))).thenReturn("order-1");

        DynamicImportExecutionResult result = executor.execute(command(plan(ImportDuplicateStrategy.OVERWRITE,
                ImportDuplicateStrategy.ERROR), workbook(group("R-1", mainRow("R-1", "SO-1"), List.of()))));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(result.errorRows()).isEmpty();
        ArgumentCaptor<DynamicRecord> captor = ArgumentCaptor.forClass(DynamicRecord.class);
        verify(records).create(eq("order"), captor.capture());
        assertThat(captor.getValue().getValues())
                .containsEntry("orderNo", "SO-1")
                .doesNotContainKeys("relateId", "orderNoTimeZone");
    }

    @Test
    void shouldReportChildrenNotExecutedWhenTheirParentFails() {
        when(records.list(eq("order"), any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(orderRecord("existing", "SO-1")));
        var result = executor.execute(command(plan(ImportDuplicateStrategy.ERROR, ImportDuplicateStrategy.SKIP),
                workbook(group("R-1", mainRow("R-1", "SO-1"), List.of(childRow("R-1", "SKU-1", 2))))));
        assertThat(result.errorRows()).extracting(ImportErrorRow::sheetKey)
                .containsExactly("order", "orderLine");
        assertThat(result.summaries().get("orderLine").errors()).isEqualTo(1);
        verify(records, never()).create(eq("orderLine"), any());
    }

    @Test
    void shouldResolveReferenceTitleBeforeCreatingRecord() {
        when(records.list(eq("order"), any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of());
        when(records.create(eq("order"), any(DynamicRecord.class))).thenReturn("order-1");
        when(recordService.resolveReference(eq(MODULE), eq("order"), eq("customerId"),
                any(DynamicReferenceResolveRequest.class))).thenReturn(referenceResolved("customer-1", "Acme"));

        DynamicImportExecutionResult result = executor.execute(command(planWithReference(),
                workbook(group("R-1", mainRowWithReference("R-1", "SO-1", "Acme"), List.of()))));

        assertThat(result.errorRows()).isEmpty();
        ArgumentCaptor<DynamicRecord> captor = ArgumentCaptor.forClass(DynamicRecord.class);
        verify(records).create(eq("order"), captor.capture());
        assertThat(captor.getValue().getValues())
                .containsEntry("orderNo", "SO-1")
                .containsEntry("customerId", "customer-1");
    }

    @Test
    void shouldResolveManyReferenceTitlesBeforeCreatingRecord() {
        when(records.list(eq("order"), any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of());
        when(records.create(eq("order"), any(DynamicRecord.class))).thenReturn("order-1");
        when(recordService.resolveReference(eq(MODULE), eq("order"), eq("tagIds"),
                any(DynamicReferenceResolveRequest.class))).thenReturn(referenceResolvedMany(
                List.of(resolvedResult("tag-1", "Urgent"), resolvedResult("tag-2", "Wholesale"))
        ));

        DynamicImportExecutionResult result = executor.execute(command(planWithManyReference(),
                workbook(group("R-1", mainRowWithManyReference("R-1", "SO-1", "Urgent,Wholesale"), List.of()))));

        assertThat(result.errorRows()).isEmpty();
        ArgumentCaptor<DynamicRecord> captor = ArgumentCaptor.forClass(DynamicRecord.class);
        verify(records).create(eq("order"), captor.capture());
        assertThat(captor.getValue().getValues())
                .containsEntry("orderNo", "SO-1")
                .containsEntry("tagIds", "tag-1,tag-2");
    }

    @Test
    void shouldReportErrorWhenReferenceTitleCannotResolve() {
        when(recordService.resolveReference(eq(MODULE), eq("order"), eq("customerId"),
                any(DynamicReferenceResolveRequest.class))).thenReturn(referenceNotFound("Missing Customer"));

        DynamicImportExecutionResult result = executor.execute(command(planWithReference(),
                workbook(group("R-1", mainRowWithReference("R-1", "SO-1", "Missing Customer"), List.of()))));

        assertThat(result.errorRows()).extracting(ImportErrorRow::message)
                .containsExactly("引用字段无法反推: Customer 未找到");
        verify(records, never()).create(eq("order"), any(DynamicRecord.class));
    }

    @Test
    void shouldOverwriteMainRecordWhenExistingMatchFound() {
        DynamicRecord existing = orderRecord("order-existing", "SO-1");
        existing.setVersion(3);
        when(records.list(eq("order"), any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(existing));
        when(records.update(eq("order"), any(DynamicRecord.class))).thenReturn(1);

        DynamicImportExecutionResult result = executor.execute(command(plan(ImportDuplicateStrategy.OVERWRITE,
                ImportDuplicateStrategy.ERROR), workbook(group("R-1", mainRow("R-1", "SO-1"), List.of()))));

        assertThat(result.updated()).isEqualTo(1);
        ArgumentCaptor<DynamicRecord> captor = ArgumentCaptor.forClass(DynamicRecord.class);
        verify(records).update(eq("order"), captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("order-existing");
        assertThat(captor.getValue().getVersion()).isEqualTo(3);
        assertThat(captor.getValue().getValue("orderNo")).isEqualTo("SO-1");
    }

    @Test
    void shouldReportImportFormulaValidationFailureBeforeCreate() {
        when(records.list(eq("order"), any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of());
        org.mockito.Mockito.doThrow(formulaException("订单号不能为空"))
                .when(records).validateImport(eq("order"), any(DynamicRecord.class), eq(null));

        DynamicImportExecutionResult result = executor.execute(command(plan(ImportDuplicateStrategy.OVERWRITE,
                ImportDuplicateStrategy.ERROR), workbook(group("R-1", mainRow("R-1", "SO-1"), List.of()))));

        assertThat(result.errorRows()).extracting(ImportErrorRow::message)
                .containsExactly("订单号不能为空");
        assertThat(result.summaries().get("order").errors()).isEqualTo(1);
        verify(records, never()).create(eq("order"), any(DynamicRecord.class));
    }

    @Test
    void shouldSkipMainRecordWhenExistingMatchFound() {
        when(records.list(eq("order"), any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(orderRecord("order-existing", "SO-1")));

        DynamicImportExecutionResult result = executor.execute(command(plan(ImportDuplicateStrategy.SKIP,
                ImportDuplicateStrategy.ERROR), workbook(group("R-1", mainRow("R-1", "SO-1"), List.of()))));

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.summaries().get("order").skipped()).isEqualTo(1);
        verify(records, never()).create(eq("order"), any(DynamicRecord.class));
        verify(records, never()).update(eq("order"), any(DynamicRecord.class));
    }

    @Test
    void shouldReportErrorWhenMainMatchesMultipleExistingRecords() {
        when(records.list(eq("order"), any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(orderRecord("order-1", "SO-1"), orderRecord("order-2", "SO-1")));

        DynamicImportExecutionResult result = executor.execute(command(plan(ImportDuplicateStrategy.OVERWRITE,
                ImportDuplicateStrategy.ERROR), workbook(group("R-1", mainRow("R-1", "SO-1"), List.of()))));

        assertThat(result.errorRows()).extracting(ImportErrorRow::message)
                .containsExactly("导入匹配到多条已有记录: orderNo");
        assertThat(result.summaries().get("order").errors()).isEqualTo(1);
        verify(records, never()).create(eq("order"), any(DynamicRecord.class));
        verify(records, never()).update(eq("order"), any(DynamicRecord.class));
    }

    @Test
    void shouldCreateFirstLevelChildWithParentForeignKey() {
        when(records.list(eq("order"), any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of());
        when(records.list(eq("orderLine"), any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of());
        when(records.create(eq("order"), any(DynamicRecord.class))).thenReturn("order-1");
        when(records.create(eq("orderLine"), any(DynamicRecord.class))).thenReturn("line-1");

        DynamicImportExecutionResult result = executor.execute(command(plan(ImportDuplicateStrategy.OVERWRITE,
                ImportDuplicateStrategy.OVERWRITE), workbook(group("R-1", mainRow("R-1", "SO-1"),
                List.of(childRow("R-1", "SKU-1", 2))))));

        assertThat(result.created()).isEqualTo(2);
        ArgumentCaptor<DynamicRecord> captor = ArgumentCaptor.forClass(DynamicRecord.class);
        verify(records).create(eq("orderLine"), captor.capture());
        assertThat(captor.getValue().getValues())
                .containsEntry("sku", "SKU-1")
                .containsEntry("qty", 2)
                .containsEntry("orderId", "order-1")
                .doesNotContainKey("relateId");
    }

    @Test
    void shouldReportErrorWhenChildMatchesMultipleExistingRecords() {
        when(records.list(eq("order"), any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of());
        when(records.list(eq("orderLine"), any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(lineRecord("line-1", "order-1", "SKU-1"),
                        lineRecord("line-2", "order-1", "SKU-1")));
        when(records.create(eq("order"), any(DynamicRecord.class))).thenReturn("order-1");

        DynamicImportExecutionResult result = executor.execute(command(plan(ImportDuplicateStrategy.OVERWRITE,
                ImportDuplicateStrategy.OVERWRITE), workbook(group("R-1", mainRow("R-1", "SO-1"),
                List.of(childRow("R-1", "SKU-1", 2))))));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.errorRows()).extracting(ImportErrorRow::sheetKey, ImportErrorRow::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "orderLine", "导入匹配到多条已有记录: sku"
                ));
        assertThat(result.summaries().get("orderLine").errors()).isEqualTo(1);
        verify(records, never()).create(eq("orderLine"), any(DynamicRecord.class));
    }

    @Test
    void shouldReportMissingRelationErrorOnChildRow() {
        when(recordService.relations(MODULE)).thenReturn(List.of());
        when(records.list(eq("order"), any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of());
        when(records.create(eq("order"), any(DynamicRecord.class))).thenReturn("order-1");

        DynamicImportExecutionResult result = executor.execute(command(plan(ImportDuplicateStrategy.OVERWRITE,
                ImportDuplicateStrategy.OVERWRITE), workbook(group("R-1", mainRow("R-1", "SO-1"),
                List.of(childRow("R-1", "SKU-1", 2))))));

        assertThat(result.errorRows()).extracting(ImportErrorRow::sheetKey, ImportErrorRow::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "orderLine", "子表未找到主子关系: orderLine"
                ));
        assertThat(result.summaries().get("orderLine").errors()).isEqualTo(1);
        verify(records, never()).create(eq("orderLine"), any(DynamicRecord.class));
    }

    private ExecuteDynamicImportCommand command(DynamicImportPlan plan, GroupedWorkbook workbook) {
        return new ExecuteDynamicImportCommand(plan, workbook);
    }

    private DynamicImportPlan plan(ImportDuplicateStrategy mainStrategy, ImportDuplicateStrategy childStrategy) {
        return new DynamicImportPlan(MODULE, null, List.of(
                new DynamicImportPlan.SheetPlan(
                        "order",
                        "order",
                        "Order",
                        true,
                        "orderNo",
                        mainStrategy,
                        List.of(
                                new DynamicImportPlan.FieldPlan("order", "relateId", "关联标识", true, false, false),
                                new DynamicImportPlan.FieldPlan("order", "orderNo", "Order No", FieldType.STRING,
                                        false, true, false),
                                new DynamicImportPlan.FieldPlan("order", "orderNoTimeZone", "Order No TimeZone",
                                        FieldType.STRING, false, false, true)
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

    private DynamicImportPlan planWithReference() {
        DynamicReferenceDescriptor reference = new DynamicReferenceDescriptor(
                "order",
                "customerId",
                MODULE,
                "customer",
                ReferenceCardinality.ONE,
                List.of()
        );
        return new DynamicImportPlan(MODULE, null, List.of(
                new DynamicImportPlan.SheetPlan(
                        "order",
                        "order",
                        "Order",
                        true,
                        "orderNo",
                        ImportDuplicateStrategy.OVERWRITE,
                        List.of(
                                new DynamicImportPlan.FieldPlan("order", "relateId", "关联标识", true, false, false),
                                new DynamicImportPlan.FieldPlan("order", "orderNo", "Order No", FieldType.STRING,
                                        false, true, false),
                                new DynamicImportPlan.FieldPlan("order", "customerId", "Customer", FieldType.STRING,
                                        false, true, false, reference)
                        )
                )
        ));
    }

    private DynamicImportPlan planWithManyReference() {
        DynamicReferenceDescriptor reference = new DynamicReferenceDescriptor(
                "order",
                "tagIds",
                MODULE,
                "tag",
                ReferenceCardinality.MANY,
                List.of()
        );
        return new DynamicImportPlan(MODULE, null, List.of(
                new DynamicImportPlan.SheetPlan(
                        "order",
                        "order",
                        "Order",
                        true,
                        "orderNo",
                        ImportDuplicateStrategy.OVERWRITE,
                        List.of(
                                new DynamicImportPlan.FieldPlan("order", "relateId", "关联标识", true, false, false),
                                new DynamicImportPlan.FieldPlan("order", "orderNo", "Order No", FieldType.STRING,
                                        false, true, false),
                                new DynamicImportPlan.FieldPlan("order", "tagIds", "Tags", FieldType.STRING,
                                        false, true, false, reference)
                        )
                )
        ));
    }

    private GroupedWorkbook workbook(ImportGroup... groups) {
        LinkedHashMap<String, ImportGroup> byKey = new LinkedHashMap<>();
        for (ImportGroup group : groups) {
            byKey.put(group.groupKey(), group);
        }
        return new GroupedWorkbook(byKey, List.of());
    }

    private ImportGroup group(String key, ParsedImportRow mainRow, List<ParsedImportRow> children) {
        ImportGroup group = new ImportGroup(key, mainRow);
        for (ParsedImportRow child : children) {
            group.addChild("orderLine", child);
        }
        return group;
    }

    private ParsedImportRow mainRow(String relateId, String orderNo) {
        LinkedHashMap<String, String> rawValues = new LinkedHashMap<>();
        rawValues.put("关联标识", relateId);
        rawValues.put("Order No", orderNo);
        rawValues.put("Order No TimeZone", "Asia/Shanghai");
        LinkedHashMap<String, String> valuesByFieldName = new LinkedHashMap<>();
        valuesByFieldName.put("relateId", relateId);
        valuesByFieldName.put("orderNo", orderNo);
        valuesByFieldName.put("orderNoTimeZone", "Asia/Shanghai");
        LinkedHashMap<String, Object> convertedValues = new LinkedHashMap<>();
        convertedValues.put("orderNo", orderNo);
        convertedValues.put("orderNoTimeZone", "Asia/Shanghai");
        return new ParsedImportRow("order", rawValues, valuesByFieldName, convertedValues);
    }

    private ParsedImportRow mainRowWithReference(String relateId, String orderNo, String customerTitle) {
        ParsedImportRow row = mainRow(relateId, orderNo);
        row.rawValues().put("Customer", customerTitle);
        row.valuesByFieldName().put("customerId", customerTitle);
        row.convertedValues().put("customerId", customerTitle);
        return row;
    }

    private ParsedImportRow mainRowWithManyReference(String relateId, String orderNo, String tagTitles) {
        ParsedImportRow row = mainRow(relateId, orderNo);
        row.rawValues().put("Tags", tagTitles);
        row.valuesByFieldName().put("tagIds", tagTitles);
        row.convertedValues().put("tagIds", tagTitles);
        return row;
    }

    private DynamicReferenceResolveResponse referenceResolved(String id, String title) {
        return new DynamicReferenceResolveResponse(
                DynamicReferenceResolveStatus.RESOLVED,
                DynamicReferenceResolveMode.TRANSLATE,
                List.of(),
                List.of(resolvedResult(id, title)),
                0,
                20,
                1
        );
    }

    private DynamicReferenceResolveResponse referenceResolvedMany(List<DynamicReferenceResolveResult> results) {
        return new DynamicReferenceResolveResponse(
                DynamicReferenceResolveStatus.RESOLVED,
                DynamicReferenceResolveMode.TRANSLATE,
                List.of(),
                results,
                0,
                20,
                results.size()
        );
    }

    private DynamicReferenceResolveResult resolvedResult(String id, String title) {
        DynamicReferenceResolveItem item = new DynamicReferenceResolveItem(
                id,
                title,
                DynamicReferenceMatchMode.LABEL,
                java.util.Map.of()
        );
        return new DynamicReferenceResolveResult(title, DynamicReferenceResolveStatus.RESOLVED,
                DynamicReferenceMatchMode.LABEL, item, List.of());
    }

    private DynamicReferenceResolveResponse referenceNotFound(String title) {
        return new DynamicReferenceResolveResponse(
                DynamicReferenceResolveStatus.NOT_FOUND,
                DynamicReferenceResolveMode.TRANSLATE,
                List.of(),
                List.of(new DynamicReferenceResolveResult(title, DynamicReferenceResolveStatus.NOT_FOUND,
                        DynamicReferenceMatchMode.LABEL, null, List.of())),
                0,
                20,
                1
        );
    }

    private ParsedImportRow childRow(String relateId, String sku, Integer qty) {
        LinkedHashMap<String, String> rawValues = new LinkedHashMap<>();
        rawValues.put("关联标识", relateId);
        rawValues.put("SKU", sku);
        rawValues.put("Qty", String.valueOf(qty));
        LinkedHashMap<String, String> valuesByFieldName = new LinkedHashMap<>();
        valuesByFieldName.put("relateId", relateId);
        valuesByFieldName.put("sku", sku);
        valuesByFieldName.put("qty", String.valueOf(qty));
        LinkedHashMap<String, Object> convertedValues = new LinkedHashMap<>();
        convertedValues.put("sku", sku);
        convertedValues.put("qty", qty);
        return new ParsedImportRow("orderLine", rawValues, valuesByFieldName, convertedValues);
    }

    private DynamicRecord orderRecord(String id, String orderNo) {
        DynamicRecord record = new DynamicRecord(orderEntity());
        record.setId(id);
        record.setValue("orderNo", orderNo);
        return record;
    }

    private DynamicFormulaException formulaException(String message) {
        FormulaRuntimeReport report = new FormulaRuntimeReport();
        report.error("importCheck", "{orderNo} != ''", message);
        return new DynamicFormulaException(MODULE, "order", report);
    }

    private DynamicRecord lineRecord(String id, String orderId, String sku) {
        DynamicRecord record = new DynamicRecord(lineEntity());
        record.setId(id);
        record.setValue("orderId", orderId);
        record.setValue("sku", sku);
        return record;
    }

    private EntityDefinition orderEntity() {
        return new EntityDefinition("order", "sales_order", "Order", List.of(
                FieldDefinition.string("orderNo", "Order No").column("order_no"),
                FieldDefinition.string("customerId", "Customer").column("customer_id"),
                FieldDefinition.string("tagIds", "Tags").column("tag_ids")
        ));
    }

    private EntityDefinition lineEntity() {
        return new EntityDefinition("order_line", "sales_order_line", "Order Line", List.of(
                FieldDefinition.string("orderId", "Order Id").column("order_id"),
                FieldDefinition.string("sku", "SKU"),
                FieldDefinition.integer("qty", "Qty")
        ));
    }
}
