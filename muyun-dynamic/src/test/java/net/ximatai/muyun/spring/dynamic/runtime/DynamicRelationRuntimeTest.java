package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.common.model.title.TitleField;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrityPolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.ability.deletion.DeletionContext;
import net.ximatai.muyun.spring.ability.deletion.DeletionNode;
import net.ximatai.muyun.spring.ability.deletion.DeletionResource;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.metadata.EntityFormulaRuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicRelationRuntimeTest {
    private static final String SCHEMA = "public";
    private static final String MODULE = "sales.invoice";

    @Test
    void shouldNotifyMutationCoordinatorForDynamicRelationChildCreate() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), anyString(), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(invoiceModule());
        RecordingMutationCoordinator coordinator = new RecordingMutationCoordinator();
        DynamicRecordService service = new DynamicRecordService(
                runtime,
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService(),
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService(),
                coordinator
        );
        DynamicRecord invoice = service.newRecord(MODULE, "invoice").setValue("title", "I-001");
        DynamicRecord line = service.newRecord(MODULE, "invoice_line").setValue("title", "L-001");
        invoice.setChildren("lines", List.of(line));

        service.create(MODULE, "invoice", invoice);

        assertThat(invoice.getId()).isNotBlank();
        assertThat(line.getId()).isNotBlank();
        assertThat(line.getValue("invoiceId")).isEqualTo(invoice.getId());
        assertThat(coordinator.events()).containsExactly(
                "beforeChildCreate:lines:invoice_line:L-001:" + invoice.getId(),
                "afterChildCreate:lines:invoice_line:L-001:" + line.getId()
        );
    }

    @Test
    void shouldNotifyMutationCoordinatorForDynamicRelationChildReplace() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceAndLineRows(operations);
        when(operations.insertItem(eq(SCHEMA), eq("app_invoice_line"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(invoiceModule());
        RecordingMutationCoordinator coordinator = new RecordingMutationCoordinator();
        DynamicRecordService service = new DynamicRecordService(
                runtime,
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService(),
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService(),
                coordinator
        );
        DynamicRecord retainedLine = service.newRecord(MODULE, "invoice_line").setValue("title", "L-001-updated");
        retainedLine.setId("line-1");
        retainedLine.setVersion(1);
        DynamicRecord newLine = service.newRecord(MODULE, "invoice_line").setValue("title", "L-003");
        DynamicRecord invoice = service.newRecord(MODULE, "invoice").setValue("title", "I-001-updated");
        invoice.setId("invoice-1");
        invoice.setVersion(1);
        invoice.setChildren("lines", List.of(retainedLine, newLine));

        service.update(MODULE, "invoice", invoice);

        assertThat(newLine.getId()).isNotBlank();
        assertThat(newLine.getValue("invoiceId")).isEqualTo("invoice-1");
        assertThat(coordinator.events()).containsExactly(
                "beforeChildUpdate:lines:line-1:L-001-updated",
                "beforeChildCreate:lines:invoice_line:L-003:invoice-1",
                "beforeChildDelete:lines:line-2:L-002",
                "afterChildUpdate:lines:line-1:L-001-updated",
                "afterChildCreate:lines:invoice_line:L-003:" + newLine.getId(),
                "afterChildDelete:lines:line-2:L-002"
        );
    }

    @Test
    void shouldInsertDynamicChildrenThroughSharedChildRelationAbility() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), anyString(), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(invoiceModule());
        DynamicEntityService invoiceService = runtime.entityService(MODULE, "invoice");
        DynamicRecord invoice = runtime.newRecord(MODULE, "invoice").setValue("title", "I-001");
        DynamicRecord line = runtime.newRecord(MODULE, "invoice_line").setValue("title", "L-001");
        invoice.setChildren("lines", List.of(line));

        String id = invoiceService.insert(invoice);

        ArgumentCaptor<String> table = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations, times(2)).insertItem(eq(SCHEMA), table.capture(), body.capture(), eq("id"));
        assertThat(id).hasSize(32);
        assertThat(table.getAllValues()).containsExactly("app_invoice", "app_invoice_line");
        assertThat(body.getAllValues().get(1))
                .containsEntry("title", "L-001")
                .containsEntry("invoice_id", id)
                .containsEntry("deleted", Boolean.FALSE);
    }

    @Test
    void shouldApplyDynamicChildFormulaBeforeChildRowsAreInserted() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), anyString(), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(formulaInvoiceModule());
        DynamicRecord invoice = runtime.newRecord(MODULE, "invoice").setValue("title", "I-001");
        DynamicRecord line = runtime.newRecord(MODULE, "invoice_line")
                .setValue("quantity", BigDecimal.valueOf(3))
                .setValue("price", BigDecimal.valueOf(11));
        invoice.setChildren("lines", List.of(line));

        runtime.entityService(MODULE, "invoice").insert(invoice);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations, times(2)).insertItem(eq(SCHEMA), anyString(), body.capture(), eq("id"));
        assertThat((BigDecimal) body.getAllValues().get(1).get("line_amount")).isEqualByComparingTo("33");
    }

    @Test
    void shouldApplyDynamicChildFormulaBeforeChildRowsAreReplaced() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (sql.contains("\"app_invoice_line\"") && sql.contains("\"invoice_id\" =")) {
                return containsParam(params, "invoice-1")
                        ? List.of(formulaLineRow("line-1", 1, 1, 1))
                        : List.of();
            }
            if (sql.contains("\"app_invoice_line\"") && sql.contains("\"id\" =")) {
                return containsParam(params, "line-1") ? List.of(formulaLineRow("line-1", 1, 1, 1)) : List.of();
            }
            if (sql.contains("\"app_invoice\"") && sql.contains("\"id\" =")) {
                return containsParam(params, "invoice-1") ? List.of(invoiceRow()) : List.of();
            }
            return List.of();
        });
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(formulaInvoiceModule());
        DynamicRecord invoice = runtime.newRecord(MODULE, "invoice").setValue("title", "I-001");
        invoice.setId("invoice-1");
        invoice.setVersion(1);
        DynamicRecord line = runtime.newRecord(MODULE, "invoice_line")
                .setValue("quantity", BigDecimal.valueOf(5))
                .setValue("price", BigDecimal.valueOf(7));
        line.setId("line-1");
        line.setVersion(1);
        invoice.setChildren("lines", List.of(line));

        runtime.entityService(MODULE, "invoice").update(invoice);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations, times(2)).patchUpdateItemWhere(eq(SCHEMA), anyString(), body.capture(), anyMap(), eq("id"));
        assertThat((BigDecimal) body.getAllValues().get(1).get("line_amount")).isEqualByComparingTo("35");
    }


    @Test
    void shouldInsertDynamicChildrenWithCurrentTenant() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), anyString(), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(invoiceModule());
        DynamicRecord invoice = runtime.newRecord(MODULE, "invoice").setValue("title", "I-001");
        DynamicRecord line = runtime.newRecord(MODULE, "invoice_line").setValue("title", "L-001");
        invoice.setChildren("lines", List.of(line));

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            runtime.entityService(MODULE, "invoice").insert(invoice);
        }

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations, times(2)).insertItem(eq(SCHEMA), anyString(), body.capture(), eq("id"));
        assertThat(body.getAllValues().get(0)).containsEntry("tenant_id", "tenant-a");
        assertThat(body.getAllValues().get(1)).containsEntry("tenant_id", "tenant-a");
    }

    @Test
    void shouldLetChildRelationSetWriteProtectedForeignKeyInternally() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), anyString(), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(writeProtectedForeignKeyInvoiceModule());
        DynamicRecord invoice = runtime.newRecord(MODULE, "invoice").setValue("title", "I-001");
        DynamicRecord line = runtime.newRecord(MODULE, "invoice_line").setValue("title", "L-001");
        invoice.setChildren("lines", List.of(line));

        String id = runtime.entityService(MODULE, "invoice").insert(invoice);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations, times(2)).insertItem(eq(SCHEMA), anyString(), body.capture(), eq("id"));
        assertThat(body.getAllValues().get(1)).containsEntry("invoice_id", id);
        DynamicRecord externalLine = runtime.newRecord(MODULE, "invoice_line")
                .setValue("title", "L-002")
                .setValue("invoiceId", id);
        assertThatThrownBy(() -> runtime.entityService(MODULE, "invoice_line").insert(externalLine))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("write protected");
    }

    @Test
    void shouldValidateExplicitDynamicReferenceTargetOnWrite() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceRows(operations);
        when(operations.insertItem(eq(SCHEMA), anyString(), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicEntityService lineService = new DynamicRecordRuntime(operations)
                .register(invoiceModule())
                .entityService(MODULE, "invoice_line");

        DynamicRecord valid = new DynamicRecord(invoiceLineEntity())
                .setValue("title", "L-001")
                .setValue("invoiceId", "invoice-1");
        lineService.insert(valid);

        DynamicRecord missing = new DynamicRecord(invoiceLineEntity())
                .setValue("title", "L-002")
                .setValue("invoiceId", "missing-invoice");
        assertThatThrownBy(() -> lineService.insert(missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dynamic reference target not found");

        DynamicRecord blankRequired = new DynamicRecord(invoiceLineEntity())
                .setValue("title", "L-003")
                .setValue("invoiceId", " ");
        assertThatThrownBy(() -> lineService.insert(blankRequired))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required dynamic reference field");
    }

    @Test
    void shouldAutoPopulateAndCascadeDeleteDynamicChildren() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceRows(operations);
        when(operations.patchUpdateItem(eq(SCHEMA), anyString(), anyString(), anyMap())).thenReturn(1);
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(invoiceModule());
        DynamicEntityService invoiceService = runtime.entityService(MODULE, "invoice");

        DynamicRecord selected = invoiceService.select("invoice-1");
        int deleted = invoiceService.delete("invoice-1");

        assertThat(selected.getChildren("lines"))
                .hasSize(1)
                .first()
                .extracting(child -> child.getValue("title"))
                .isEqualTo("L-001");
        assertThat(deleted).isEqualTo(1);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        ArgumentCaptor<String> table = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> where = mapCaptor();
        verify(operations, times(2)).patchUpdateItemWhere(eq(SCHEMA), table.capture(), body.capture(), where.capture(), eq("id"));
        assertThat(table.getAllValues()).containsExactly("app_invoice_line", "app_invoice");
        assertThat(where.getAllValues().get(0)).containsEntry("id", "line-1");
        assertThat(where.getAllValues().get(1)).containsEntry("id", "invoice-1");
        assertThat(body.getAllValues().get(0)).containsEntry("deleted", Boolean.TRUE);
    }

    @Test
    void shouldCascadeDynamicReferenceReferrersThroughRuntimeIndex() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceRows(operations);
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(cascadeInvoiceModule());
        DeletionContext context = DeletionContext.root(MODULE + ".invoice", "invoice-1");
        DeletionNode node = DeletionNode.transientNode(new DeletionResource(MODULE + ".invoice", "invoice-1"));

        runtime.cascadeReferenceTargetUnavailable(ReferenceTarget.of(MODULE, "invoice"), "invoice-1", context, node);

        ArgumentCaptor<String> table = ArgumentCaptor.forClass(String.class);
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), table.capture(), anyMap(), anyMap(), eq("id"));
        assertThat(table.getValue()).isEqualTo("app_invoice_line");
    }

    @Test
    void shouldRebuildDynamicReferenceCascadeIndexAfterRefresh() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceRows(operations);
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(invoiceModule());
        DeletionContext context = DeletionContext.root(MODULE + ".invoice", "invoice-1");
        DeletionNode node = DeletionNode.transientNode(new DeletionResource(MODULE + ".invoice", "invoice-1"));

        runtime.refresh(cascadeInvoiceModule());
        runtime.cascadeReferenceTargetUnavailable(ReferenceTarget.of(MODULE, "invoice"), "invoice-1", context, node);

        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq("app_invoice_line"), anyMap(), anyMap(), eq("id"));
    }

    @Test
    void shouldRestrictDynamicReferenceTargetWithoutCascadeSideEffect() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceRows(operations);
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(restrictInvoiceModule());

        assertThatThrownBy(() -> runtime.validateReferenceTargetDeletion(
                ReferenceTarget.of(MODULE, "invoice"), "invoice-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("cannot make reference target unavailable");

        verify(operations, never()).patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString());
    }

    @Test
    void shouldRestrictStaticReferenceTargetThroughTheSameRuntimeIndex() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "line-1", "student_id", "student-1"
        )));
        EntityDefinition line = new EntityDefinition("invoice_line", "app_invoice_line", "Invoice Line",
                List.of(FieldDefinition.string("studentId", "Student").column("student_id")));
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(line))
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "studentId",
                                ReferenceTarget.of("education", "student"))
                        .withIntegrity(new ReferenceIntegrityPolicy(ReferenceTargetUnavailablePolicy.RESTRICT))))
                .build();
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(module);

        assertThatThrownBy(() -> runtime.validateReferenceTargetDeletion(
                ReferenceTarget.of("education", "student"), "student-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("cannot make reference target unavailable");

        verify(operations, never()).patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString());
    }

    @Test
    void shouldReplaceDynamicChildrenThroughSharedChildRelationAbility() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (sql.contains("\"app_invoice_line\"") && sql.contains("\"invoice_id\" =")) {
                return containsParam(params, "invoice-1")
                        ? List.of(lineRow("line-1", "L-001", 1), lineRow("line-2", "L-002", 2))
                        : List.of();
            }
            if (sql.contains("\"app_invoice_line\"") && sql.contains("\"id\" =")) {
                if (containsParam(params, "line-1")) {
                    return List.of(lineRow("line-1", "L-001", 1));
                }
                if (containsParam(params, "line-2")) {
                    return List.of(lineRow("line-2", "L-002", 2));
                }
                return List.of();
            }
            if (sql.contains("\"app_invoice\"") && sql.contains("\"id\" =")) {
                return containsParam(params, "invoice-1") ? List.of(invoiceRow()) : List.of();
            }
            return List.of();
        });
        when(operations.insertItem(eq(SCHEMA), eq("app_invoice_line"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(invoiceModule());
        DynamicEntityService invoiceService = runtime.entityService(MODULE, "invoice");
        DynamicRecord retainedLine = runtime.newRecord(MODULE, "invoice_line").setValue("title", "L-001-updated");
        retainedLine.setId("line-1");
        retainedLine.setVersion(1);
        DynamicRecord newLine = runtime.newRecord(MODULE, "invoice_line").setValue("title", "L-003");
        DynamicRecord invoice = runtime.newRecord(MODULE, "invoice").setValue("title", "I-001-updated");
        invoice.setId("invoice-1");
        invoice.setVersion(1);
        invoice.setChildren("lines", List.of(retainedLine, newLine));

        int updated = invoiceService.update(invoice);

        assertThat(updated).isEqualTo(1);
        ArgumentCaptor<String> table = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        ArgumentCaptor<Map<String, Object>> where = mapCaptor();
        verify(operations, times(3)).patchUpdateItemWhere(eq(SCHEMA), table.capture(), body.capture(), where.capture(), eq("id"));
        assertThat(table.getAllValues()).containsExactly("app_invoice", "app_invoice_line", "app_invoice_line");
        assertThat(body.getAllValues().get(1))
                .containsEntry("title", "L-001-updated")
                .containsEntry("invoice_id", "invoice-1");
        assertThat(body.getAllValues().get(2))
                .containsEntry("deleted", Boolean.TRUE);
        assertThat(where.getAllValues().get(2)).containsEntry("id", "line-2");

        ArgumentCaptor<Map<String, Object>> inserted = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq("app_invoice_line"), inserted.capture(), eq("id"));
        assertThat(inserted.getValue())
                .containsEntry("title", "L-003")
                .containsEntry("invoice_id", "invoice-1")
                .containsEntry("deleted", Boolean.FALSE);
    }

    @Test
    void shouldRejectDuplicateAndForeignDynamicChildIdsOnReplace() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceAndLineRows(operations);
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(invoiceModule());
        DynamicEntityService invoiceService = runtime.entityService(MODULE, "invoice");
        DynamicRecord retainedLine = runtime.newRecord(MODULE, "invoice_line").setValue("title", "L-001");
        retainedLine.setId("line-1");
        retainedLine.setVersion(1);
        DynamicRecord foreignLine = runtime.newRecord(MODULE, "invoice_line").setValue("title", "Foreign");
        foreignLine.setId("line-foreign");
        foreignLine.setVersion(1);
        DynamicRecord invoice = runtime.newRecord(MODULE, "invoice").setValue("title", "I-001");
        invoice.setId("invoice-1");
        invoice.setVersion(1);

        invoice.setChildren("lines", List.of(retainedLine, retainedLine));
        assertThatThrownBy(() -> invoiceService.update(invoice))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Duplicate child id");

        invoice.setChildren("lines", List.of(foreignLine));
        assertThatThrownBy(() -> invoiceService.update(invoice))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not belong to parent");
    }

    @Test
    void shouldRejectDuplicateAndForeignDynamicChildIdsOnInsert() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceAndLineRows(operations);
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(invoiceModule());
        DynamicEntityService invoiceService = runtime.entityService(MODULE, "invoice");
        DynamicRecord duplicateLine = runtime.newRecord(MODULE, "invoice_line").setValue("title", "Duplicate");
        duplicateLine.setId("line-new");
        duplicateLine.setVersion(1);
        DynamicRecord foreignLine = runtime.newRecord(MODULE, "invoice_line").setValue("title", "Existing");
        foreignLine.setId("line-foreign");
        foreignLine.setVersion(1);
        DynamicRecord invoice = runtime.newRecord(MODULE, "invoice").setValue("title", "I-001");

        invoice.setChildren("lines", List.of(duplicateLine, duplicateLine));
        assertThatThrownBy(() -> invoiceService.insert(invoice))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Duplicate child id");

        invoice.setChildren("lines", List.of(foreignLine));
        assertThatThrownBy(() -> invoiceService.insert(invoice))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not belong to parent");
    }

    @Test
    void shouldAutoPopulateDynamicChildrenWithSortCapabilityOrderSql() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (sql.contains("\"app_invoice_line\"") && sql.contains("\"invoice_id\" =")) {
                return containsParam(params, "invoice-1")
                        ? List.of(lineRow("line-1", "L-001", 1), lineRow("line-2", "L-002", 2))
                        : List.of();
            }
            if (sql.contains("\"app_invoice\"") && sql.contains("\"id\" =")) {
                return containsParam(params, "invoice-1") ? List.of(invoiceRow()) : List.of();
            }
            return List.of();
        });
        DynamicEntityService invoiceService = new DynamicRecordRuntime(operations)
                .register(sortableInvoiceModule())
                .entityService(MODULE, "invoice");

        DynamicRecord selected = invoiceService.select("invoice-1");

        assertThat(selected.getChildren("lines"))
                .extracting(child -> child.getValue("title"))
                .containsExactly("L-001", "L-002");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(statement -> assertThat(statement)
                .contains("\"app_invoice_line\"")
                .contains("\"invoice_id\" =")
                .contains("ORDER BY \"sort_order\" ASC"));
    }

    @Test
    void shouldApplyTenantScopeWhenPopulatingAndCascadingDynamicChildren() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceRows(operations);
        when(operations.patchUpdateItem(eq(SCHEMA), anyString(), anyString(), anyMap())).thenReturn(1);
        DynamicEntityService invoiceService = new DynamicRecordRuntime(operations)
                .register(invoiceModule())
                .entityService(MODULE, "invoice");

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            invoiceService.select("invoice-1");
            invoiceService.delete("invoice-1");
        }

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).allSatisfy(statement -> assertThat(statement).contains("\"tenant_id\" ="));
    }

    @Test
    void shouldReloadDynamicChildrenWhenParentRecordHitsCache() {
        IDatabaseOperations<Object> operations = operations();
        AtomicReference<String> lineTitle = new AtomicReference<>("L-001");
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (sql.contains("\"app_invoice_line\"") && sql.contains("\"invoice_id\" =")) {
                return params.containsValue("invoice-1") ? List.of(lineRow(lineTitle.get())) : List.of();
            }
            if (sql.contains("\"app_invoice\"") && sql.contains("\"id\" =")) {
                return params.containsValue("invoice-1") ? List.of(invoiceRow()) : List.of();
            }
            return List.of();
        });
        DynamicEntityService invoiceService = new DynamicRecordRuntime(operations)
                .register(invoiceModule())
                .entityService(MODULE, "invoice");

        DynamicRecord first = invoiceService.select("invoice-1");
        lineTitle.set("L-002");
        DynamicRecord second = invoiceService.select("invoice-1");

        assertThat(first.getChildren("lines"))
                .first()
                .extracting(child -> child.getValue("title"))
                .isEqualTo("L-001");
        assertThat(second.getChildren("lines"))
                .first()
                .extracting(child -> child.getValue("title"))
                .isEqualTo("L-002");
    }

    @Test
    void shouldInvalidateDynamicReferrerCacheWhenReferenceTargetChanges() {
        IDatabaseOperations<Object> operations = operations();
        AtomicReference<String> lineTitle = new AtomicReference<>("L-001");
        AtomicReference<String> invoiceTitle = new AtomicReference<>("I-001");
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (sql.contains("\"app_invoice_line\"") && sql.contains("\"id\" =")) {
                return params.containsValue("line-1") ? List.of(lineRow(lineTitle.get())) : List.of();
            }
            if (sql.contains("\"app_invoice\"") && sql.contains("\"id\" =")) {
                return containsParam(params, "invoice-1") ? List.of(invoiceRow(invoiceTitle.get())) : List.of();
            }
            if (sql.contains("\"app_invoice\"") && sql.contains("\"id\" IN")) {
                return containsParam(params, "invoice-1") ? List.of(invoiceRow(invoiceTitle.get())) : List.of();
            }
            return List.of();
        });
        when(operations.patchUpdateItemWhere(eq(SCHEMA), anyString(), anyMap(), anyMap(), eq("id"))).thenReturn(1);
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(invoiceModule());
        DynamicEntityService invoiceService = runtime.entityService(MODULE, "invoice");
        DynamicEntityService lineService = runtime.entityService(MODULE, "invoice_line");

        DynamicRecord first = lineService.select("line-1");
        lineTitle.set("L-002");
        invoiceTitle.set("I-002");
        DynamicRecord invoice = new DynamicRecord(invoiceEntity()).setValue("title", "I-002");
        invoice.setId("invoice-1");
        invoice.setVersion(1);
        invoiceService.update(invoice);
        DynamicRecord second = lineService.select("line-1");

        assertThat(first.getValue("title")).isEqualTo("L-001");
        assertThat(first.getValue("invoiceDisplayTitle")).isEqualTo("I-001");
        assertThat(second.getValue("title")).isEqualTo("L-002");
        assertThat(second.getValue("invoiceDisplayTitle")).isEqualTo("I-002");
    }

    @Test
    void shouldCollectDynamicReferenceIdsByMetadata() {
        IDatabaseOperations<Object> operations = operations();
        DynamicEntityService lineService = new DynamicRecordRuntime(operations)
                .register(invoiceModule())
                .entityService(MODULE, "invoice_line");
        DynamicRecord line = new DynamicRecord(invoiceLineEntity())
                .setValue("invoiceId", "invoice-1")
                .setValue("title", "L-001");

        assertThat(lineService.collectReferenceIdsByTarget(line))
                .containsEntry(ReferenceTarget.of("sales.invoice", "invoice"), Set.of("invoice-1"));
    }

    @Test
    void shouldResolveDynamicReferenceDependencyScopePlan() {
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations())
                .register(scoreModule())
                .register(studentModule());
        DynamicReferenceDependencyScopeResolver resolver = new DynamicReferenceDependencyScopeResolver(runtime);

        assertThat(resolver.resolve(new net.ximatai.muyun.spring.common.platform.ReferenceDependencyScopeRequest(
                "sales.score", "score.studentId", "view")))
                .get()
                .satisfies(plan -> {
                    assertThat(plan.sourceField()).isEqualTo("studentId");
                    assertThat(plan.targetModuleAlias()).isEqualTo("school.student");
                    assertThat(plan.targetEntityAlias()).isEqualTo("student");
                    assertThat(plan.targetTableName()).isEqualTo("school_student");
                    assertThat(plan.resolveTargetColumn("authUserId")).isEqualTo("auth_user_id");
                });
        assertThat(resolver.resolve(new net.ximatai.muyun.spring.common.platform.ReferenceDependencyScopeRequest(
                "sales.score", "studentId", "view"))).isPresent();
        assertThat(resolver.resolve(new net.ximatai.muyun.spring.common.platform.ReferenceDependencyScopeRequest(
                "sales.score", "line.studentId", "view"))).isEmpty();
        assertThat(resolver.resolveCandidates("sales.score"))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.referenceFieldId()).isEqualTo("score.studentId");
                    assertThat(candidate.targetModuleAlias()).isEqualTo("school.student");
                    assertThat(candidate.referenceActionCode()).isEqualTo("view");
                });
    }

    @Test
    void shouldNotExposeDataScopeColumnsWhenReferenceTargetDoesNotSupportDataScope() {
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations())
                .register(scoreModule())
                .register(studentModuleWithoutDataScope());
        DynamicReferenceDependencyScopeResolver resolver = new DynamicReferenceDependencyScopeResolver(runtime);

        assertThat(resolver.resolve(new net.ximatai.muyun.spring.common.platform.ReferenceDependencyScopeRequest(
                "sales.score", "studentId", "view")))
                .get()
                .satisfies(plan -> assertThatThrownBy(() -> plan.resolveTargetColumn("authUserId"))
                        .isInstanceOf(IllegalArgumentException.class));
        assertThat(resolver.resolveCandidates("sales.score")).isEmpty();
    }

    @Test
    void shouldCollectDynamicManyReferenceIdsByMetadata() {
        IDatabaseOperations<Object> operations = operations();
        DynamicEntityService lineService = new DynamicRecordRuntime(operations)
                .register(manyReferenceInvoiceModule())
                .entityService(MODULE, "invoice_line");
        DynamicRecord line = new DynamicRecord(invoiceLineEntity())
                .setValue("invoiceId", "invoice-1, invoice-2, invoice-1")
                .setValue("title", "L-001");

        assertThat(lineService.collectReferenceIdsByTarget(line))
                .containsEntry(ReferenceTarget.of("sales.invoice", "invoice"), Set.of("invoice-1", "invoice-2"));
        assertThat(manyReferenceInvoiceModule().references())
                .extracting(reference -> reference.plan().cardinality())
                .containsExactly(ReferenceCardinality.MANY);
    }

    @Test
    void shouldCompressManyDynamicReferenceProjectionValuesToExistingNonNullTargets() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (sql.contains("\"app_invoice\"") && sql.contains("\"id\" IN")) {
                return containsParam(params, "invoice-1") ? List.of(invoiceRow()) : List.of();
            }
            return List.of();
        });
        DynamicEntityService lineService = new DynamicRecordRuntime(operations)
                .register(manyProjectionInvoiceModule())
                .entityService(MODULE, "invoice_line");
        DynamicRecord line = new DynamicRecord(invoiceLineEntity())
                .setValue("invoiceId", "invoice-1, missing-invoice")
                .setValue("title", "L-001");

        lineService.afterReferenceSelect(line);

        assertThat(line.getValue("invoiceDisplayTitle")).isEqualTo(List.of("I-001"));
    }

    @Test
    void shouldCompileDynamicChildRelationToPlan() {
        ModuleDefinition module = invoiceModule();
        assertThat(module.relations())
                .extracting(relation -> relation.plan(MODULE, module.references()))
                .containsExactly(new net.ximatai.muyun.spring.ability.child.ChildPlan(
                        "lines",
                        "invoice",
                        "invoice_line",
                        "invoiceId",
                        true,
                        true
                ));
    }

    @Test
    void shouldNormalizeDynamicReferenceDefinitionDefaults() {
        EntityReferenceDefinition reference = new EntityReferenceDefinition(
                "invoice_line",
                "invoiceId",
                ReferenceTarget.of("sales.invoice", "invoice").qualifiedName(),
                null,
                List.of(), null, null, null, null, Set.of(), List.of(), List.of(), null
        );

        assertThat(reference.cardinality()).isEqualTo(ReferenceCardinality.ONE);
        assertThat(reference.projections()).isEmpty();
    }

    @Test
    void shouldAutoPopulateDynamicReferenceTitleByMetadata() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceRows(operations);
        DynamicEntityService lineService = new DynamicRecordRuntime(operations)
                .register(invoiceModule())
                .entityService(MODULE, "invoice_line");

        DynamicRecord line = lineService.select("line-1");

        assertThat(line.getValue("invoiceTitle")).isEqualTo("I-001");
        assertThat(line.getValue("invoiceDisplayTitle")).isEqualTo("I-001");
        assertThat(line.getValues())
                .containsEntry("invoiceTitle", "I-001")
                .containsEntry("invoiceDisplayTitle", "I-001");
        assertThat(line.getPlatformValues())
                .doesNotContainKey("invoiceTitle")
                .doesNotContainKey("invoiceDisplayTitle");
    }

    @Test
    void shouldPopulateDynamicReferenceProjectionWithoutAutoTitle() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceRows(operations);
        DynamicEntityService lineService = new DynamicRecordRuntime(operations)
                .register(projectionOnlyInvoiceModule())
                .entityService(MODULE, "invoice_line");

        DynamicRecord line = lineService.select("line-1");

        assertThat(line.getValue("invoiceDisplayTitle")).isEqualTo("I-001");
        assertThat(line.getValues()).containsEntry("invoiceDisplayTitle", "I-001");
        assertThat(line.getPlatformValues()).doesNotContainKey("invoiceDisplayTitle");
    }

    @Test
    void shouldAllowNullDynamicReferenceProjectionValues() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (sql.contains("\"app_invoice_line\"") && sql.contains("\"id\" =")) {
                return params.containsValue("line-1") ? List.of(lineRow()) : List.of();
            }
            if (sql.contains("\"app_invoice\"") && sql.contains("\"id\" IN")) {
                return containsParam(params, "invoice-1") ? List.of(invoiceRowWithNullTitle()) : List.of();
            }
            return List.of();
        });
        DynamicEntityService lineService = new DynamicRecordRuntime(operations)
                .register(projectionOnlyInvoiceModule())
                .entityService(MODULE, "invoice_line");

        DynamicRecord line = lineService.select("line-1");

        assertThat(line.getValue("invoiceDisplayTitle")).isNull();
        assertThat(line.getValues()).containsEntry("invoiceDisplayTitle", null);
        assertThat(line.getPlatformValues()).doesNotContainKey("invoiceDisplayTitle");
    }

    @Test
    void shouldPopulateDynamicReferenceTitleBeforeLifecycleAfterSelect() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceRows(operations);
        AtomicReference<Object> lifecycleTitle = new AtomicReference<>();
        DynamicRecordLifecycle lifecycle = new DynamicRecordLifecycle() {
            @Override
            public void afterSelect(DynamicRecord record) {
                lifecycleTitle.set(record.getValue("invoiceTitle"));
            }
        };
        DynamicEntityService lineService = new DynamicRecordRuntime(operations)
                .register(invoiceModule())
                .entityService(MODULE, "invoice_line", lifecycle);

        lineService.select("line-1");

        assertThat(lifecycleTitle).hasValue("I-001");
    }

    @Test
    void shouldResolveDynamicReferenceByRawTargetRecord() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceRows(operations);
        AtomicInteger invoiceAfterSelectCount = new AtomicInteger();
        AtomicReference<DynamicEntityService> invoiceService = new AtomicReference<>();
        AtomicReference<DynamicEntityService> lineService = new AtomicReference<>();
        ModuleDefinition module = invoiceModule();
        invoiceService.set(DynamicEntityService.withModule(
                new DynamicRecordDao(operations, invoiceEntity()),
                MODULE,
                new DynamicRecordLifecycle() {
                    @Override
                    public void afterSelect(DynamicRecord record) {
                        invoiceAfterSelectCount.incrementAndGet();
                    }
                },
                module,
                entityAlias -> "invoice_line".equals(entityAlias) ? lineService.get() : invoiceService.get()
        ));
        lineService.set(DynamicEntityService.withModule(
                new DynamicRecordDao(operations, invoiceLineEntity()),
                MODULE,
                DynamicRecordLifecycle.NONE,
                module,
                entityAlias -> "invoice".equals(entityAlias) ? invoiceService.get() : lineService.get()
        ));

        DynamicRecord line = lineService.get().select("line-1");

        assertThat(line.getValue("invoiceTitle")).isEqualTo("I-001");
        assertThat(line.getValue("invoiceDisplayTitle")).isEqualTo("I-001");
        assertThat(invoiceAfterSelectCount).hasValue(0);

        invoiceService.get().select("invoice-1");
        assertThat(invoiceAfterSelectCount).hasValue(1);
    }

    @Test
    void shouldResolveDynamicReferenceTitleWithoutAutoPopulatingChildren() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceRows(operations);
        DynamicEntityService invoiceService = new DynamicRecordRuntime(operations)
                .register(invoiceModule())
                .entityService(MODULE, "invoice");

        assertThat(invoiceService.title("invoice-1")).isEqualTo("I-001");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).noneSatisfy(value -> assertThat(value).contains("\"app_invoice_line\""));
    }

    @Test
    void shouldRejectReferenceCollectionForDifferentDynamicEntity() {
        IDatabaseOperations<Object> operations = operations();
        DynamicEntityService lineService = new DynamicRecordRuntime(operations)
                .register(invoiceModule())
                .entityService(MODULE, "invoice_line");

        assertThatThrownBy(() -> lineService.collectReferenceIdsByTarget(new DynamicRecord(invoiceEntity())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entity mismatch");
    }

    @Test
    void shouldRejectUnknownDynamicChildRelationPayload() {
        IDatabaseOperations<Object> operations = operations();
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(invoiceModule());
        DynamicRecord invoice = runtime.newRecord(MODULE, "invoice").setValue("title", "I-001");
        invoice.setChildren("unknown_lines", List.of(runtime.newRecord(MODULE, "invoice_line").setValue("title", "L-001")));

        assertThatThrownBy(() -> runtime.entityService(MODULE, "invoice").insert(invoice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown dynamic child relation");
    }

    @Test
    void shouldExposeDynamicChildRelationCodeThroughTheSharedChildRelationContract() {
        DynamicEntityService invoiceService = new DynamicRecordRuntime(operations())
                .register(invoiceModule())
                .entityService(MODULE, "invoice");

        assertThat(invoiceService.childRelations())
                .extracting(relation -> relation.relationCode())
                .containsExactly("lines");
    }

    @Test
    void shouldReadDynamicAggregateExpansionThroughTheSameChildRelationBoundary() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceRows(operations);
        DynamicRecordService service = new DynamicRecordService(
                new DynamicRecordRuntime(operations).register(invoiceModule()));

        List<DynamicRecord> rows = service.aggregateChildrenForView(MODULE, "invoice-1", "lines");

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo("line-1");
            assertThat(row.getValue("invoiceId")).isEqualTo("invoice-1");
            assertThat(row.getValue("title")).isEqualTo("L-001");
        });
    }

    @Test
    void shouldEnrichAggregateChildrenAfterParentViewEvenWhenChildQueryScopeExcludesThem() {
        IDatabaseOperations<Object> operations = operations();
        stubInvoiceRows(operations);
        DataScopeCriteriaService scopes = new DataScopeCriteriaService() {
            @Override
            public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                            String actionCode,
                                                            net.ximatai.muyun.database.core.orm.Criteria criteria,
                                                            java.util.Optional<net.ximatai.muyun.spring.common.identity.CurrentUser> currentUser) {
                return PlatformAction.VIEW.code().equals(actionCode)
                        ? DataScopeCriteriaResult.restricted(criteria.eq("id", "invoice-1"))
                        : DataScopeCriteriaResult.restricted(criteria.eq("id", "query-blocked"));
            }

            @Override
            public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                            ActionExecutionPolicy policy,
                                                            net.ximatai.muyun.database.core.orm.Criteria criteria,
                                                            java.util.Optional<net.ximatai.muyun.spring.common.identity.CurrentUser> currentUser) {
                return resolveReadScope(moduleAlias, policy.permissionActionCode(), criteria, currentUser);
            }
        };
        DynamicRecordService service = new DynamicRecordService(
                new DynamicRecordRuntime(operations).register(invoiceModule()),
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService(), scopes);

        assertThat(service.list(MODULE, "invoice_line", net.ximatai.muyun.database.core.orm.Criteria.of(),
                net.ximatai.muyun.database.core.orm.PageRequest.of(1, 20))).isEmpty();
        assertThat(service.aggregateChildrenForView(MODULE, "invoice-1", "lines"))
                .singleElement().satisfies(line -> {
                    assertThat(line.getId()).isEqualTo("line-1");
                    assertThat(line.getValue("invoiceTitle")).isEqualTo("I-001");
                });
    }

    @Test
    void shouldIncludeDynamicReferencePresentationCompanionsInAggregateExpansionFields() {
        DynamicRecordService service = new DynamicRecordService(
                new DynamicRecordRuntime(operations()).register(invoiceModule()));

        assertThat(service.aggregateExpansionOutputFields(MODULE, "lines", List.of("invoiceId", "title")))
                .containsExactly("invoiceId", "title", "invoiceTitle", "invoiceDisplayTitle");
    }

    @Test
    void shouldRejectMismatchedDynamicChildEntityPayload() {
        IDatabaseOperations<Object> operations = operations();
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(invoiceModule());
        DynamicRecord invoice = runtime.newRecord(MODULE, "invoice").setValue("title", "I-001");
        invoice.setChildren("lines", List.of(runtime.newRecord(MODULE, "invoice").setValue("title", "Wrong")));

        assertThatThrownBy(() -> runtime.entityService(MODULE, "invoice").insert(invoice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dynamic child entity mismatch");
    }

    @Test
    void shouldRejectInvalidDynamicRelationMetadata() {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of(EntityRelationDefinition.child("lines", "invoice", "invoice_line", "missingField")))
                .build();

        assertThatThrownBy(() -> new ModuleDefinitionValidator().validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("invoice_line.missingField");
    }

    @Test
    void shouldRejectNonStringDynamicRelationForeignKeyMetadata() {
        EntityDefinition invalidLine = new EntityDefinition(
                "invoice_line",
                "app_invoice_line",
                "Invoice Line",
                List.of(
                        FieldDefinition.integer("invoiceId", "Invoice").column("invoice_id").required().indexed(),
                        FieldDefinition.titleField().required()
                )
        ).withCapabilities(EntityCapability.REFERENCE);
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invalidLine))
                .relations(List.of(EntityRelationDefinition.child("lines", "invoice", "invoice_line", "invoiceId")))
                .build();

        assertThatThrownBy(() -> new ModuleDefinitionValidator().validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("relation child foreign key field must be STRING")
                .hasMessageContaining("invoice_line.invoiceId");
    }

    @Test
    void shouldRejectMalformedReferenceTargetModuleAlias() {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of())
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId", "sales-.invoice")))
                .build();

        assertThatThrownBy(() -> new ModuleDefinitionValidator().validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("reference target module alias");
    }

    @Test
    void shouldRejectUnparseableDynamicReferenceTargetMetadata() {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of())
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId", "sales")))
                .build();

        assertThatThrownBy(() -> new ModuleDefinitionValidator().validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("invalid reference target qualified name");
    }

    @Test
    void shouldAllowDynamicReferenceAutoTitleAcrossModules() {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of())
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId", ReferenceTarget.of("other.invoice", "invoice"))
                        .withProjection("title", "invoiceTitle")))
                .build();

        new ModuleDefinitionValidator().validate(module);
    }

    @Test
    void shouldRejectUnknownSameModuleDynamicReferenceTarget() {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of())
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId", ReferenceTarget.of("sales.invoice", "missing_invoice"))))
                .build();

        assertThatThrownBy(() -> new ModuleDefinitionValidator().validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("reference target entity");
    }

    @Test
    void shouldRejectDynamicReferenceLoadFieldConflicts() {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of())
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId", ReferenceTarget.of("sales.invoice", "invoice"))
                        .withProjection("title", "title")))
                .build();

        assertThatThrownBy(() -> new ModuleDefinitionValidator().validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("projection output field conflicts");
    }

    @Test
    void shouldRejectDynamicReferenceDisplayWhenTargetHasNoReferenceCapability() {
        EntityDefinition invoiceWithoutTitle = new EntityDefinition(
                "invoice",
                "app_invoice",
                "Invoice",
                List.of(FieldDefinition.string("code", "Code"))
        );
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceWithoutTitle, invoiceLineEntity()))
                .relations(List.of())
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId", ReferenceTarget.of("sales.invoice", "invoice"))
                        .withProjection("title", "invoiceTitle")))
                .build();

        assertThatThrownBy(() -> new ModuleDefinitionValidator().validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("target requires REFERENCE capability");
    }

    @Test
    void shouldAllowDynamicReferenceProjectionAcrossModules() {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of())
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId", ReferenceTarget.of("other.invoice", "invoice"))
                        .withProjection("title", "invoiceDisplayTitle")))
                .build();

        new ModuleDefinitionValidator().validate(module);
    }

    @Test
    void shouldRejectDynamicReferenceProjectionFieldConflicts() {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of())
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId", ReferenceTarget.of("sales.invoice", "invoice"))
                        .withProjection("title", "title")))
                .build();

        assertThatThrownBy(() -> new ModuleDefinitionValidator().validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("projection output field conflicts");
    }

    @Test
    void shouldNormalizeDuplicateDynamicReferenceLoads() {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of())
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId", ReferenceTarget.of("sales.invoice", "invoice"))
                        .withProjection("title", "invoiceDisplay")
                        .withProjection("title", "invoiceDisplay")))
                .build();

        new ModuleDefinitionValidator().validate(module);
    }

    @Test
    void shouldAllowSameRelationCodeOnDifferentDynamicParentEntities() {
        EntityDefinition payment = new EntityDefinition(
                "payment",
                "app_payment",
                "Payment",
                List.of(FieldDefinition.titleField().required())
        ).withCapabilities(EntityCapability.REFERENCE);
        EntityDefinition paymentLine = new EntityDefinition(
                "payment_line",
                "app_payment_line",
                "Payment Line",
                List.of(
                        FieldDefinition.string("paymentId", "Payment").column("payment_id").length(64).required(),
                        FieldDefinition.titleField().required()
                )
        ).withCapabilities(EntityCapability.REFERENCE);
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity(), payment, paymentLine))
                .relations(List.of(
                        EntityRelationDefinition.child("lines", "invoice", "invoice_line", "invoiceId"),
                        EntityRelationDefinition.child("lines", "payment", "payment_line", "paymentId")
                ))
                .build();

        new ModuleDefinitionValidator().validate(module);
    }

    private void stubInvoiceRows(IDatabaseOperations<Object> operations) {
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (sql.contains("\"app_invoice_line\"") && sql.contains("\"id\" =")) {
                return params.containsValue("line-1") ? List.of(lineRow()) : List.of();
            }
            if (sql.contains("\"app_invoice_line\"") && sql.contains("\"invoice_id\" =")) {
                return containsParam(params, "invoice-1") ? List.of(lineRow()) : List.of();
            }
            if (sql.contains("\"app_invoice\"") && sql.contains("\"id\" =")) {
                return containsParam(params, "invoice-1") ? List.of(invoiceRow()) : List.of();
            }
            if (sql.contains("\"app_invoice\"") && sql.contains("\"id\" IN")) {
                return containsParam(params, "invoice-1") ? List.of(invoiceRow()) : List.of();
            }
            return List.of();
        });
    }

    private boolean containsParam(Map<String, Object> params, Object expected) {
        return params.values().stream().anyMatch(value -> value instanceof Iterable<?> values
                ? containsIterable(values, expected)
                : expected.equals(value));
    }

    private boolean containsIterable(Iterable<?> values, Object expected) {
        for (Object value : values) {
            if (expected.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private ModuleDefinition invoiceModule() {
        return ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of(EntityRelationDefinition.child("lines", "invoice", "invoice_line", "invoiceId")
                        .withAutoPopulate()
                        ))
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId", ReferenceTarget.of("sales.invoice", "invoice"))
                        .withProjection("title", "invoiceTitle")
                        .withProjection("title", "invoiceDisplayTitle")
                        .withIntegrity(new ReferenceIntegrityPolicy(
                                ReferenceTargetUnavailablePolicy.CASCADE_DELETE))))
                .build();
    }

    private ModuleDefinition cascadeInvoiceModule() {
        return ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of())
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId",
                                ReferenceTarget.of(MODULE, "invoice"))
                        .withIntegrity(new ReferenceIntegrityPolicy(ReferenceTargetUnavailablePolicy.CASCADE_DELETE))))
                .build();
    }

    private ModuleDefinition restrictInvoiceModule() {
        return ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of())
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId",
                                ReferenceTarget.of(MODULE, "invoice"))
                        .withIntegrity(new ReferenceIntegrityPolicy(ReferenceTargetUnavailablePolicy.RESTRICT))))
                .build();
    }

    private ModuleDefinition scoreModule() {
        return ModuleDefinition.builder("sales.score", "Score")
                .entities(List.of(new EntityDefinition("score", "sales_score", "Score", List.of(
                        FieldDefinition.titleField(),
                        FieldDefinition.string("studentId", "Student").column("student_id")
                ), Set.of(EntityCapability.CRUD, EntityCapability.REFERENCE))))
                .relations(List.of())
                .references(List.of(EntityReferenceDefinition.to("score", "studentId",
                        ReferenceTarget.of("school.student", "student"))))
                .build();
    }

    private ModuleDefinition studentModule() {
        return new ModuleDefinition(
                "school.student",
                "Student",
                List.of(new EntityDefinition("student", "school_student", "Student", List.of(
                        FieldDefinition.titleField()
                ), Set.of(EntityCapability.CRUD, EntityCapability.REFERENCE, EntityCapability.DATA_SCOPE)))
        );
    }

    private ModuleDefinition studentModuleWithoutDataScope() {
        return new ModuleDefinition(
                "school.student",
                "Student",
                List.of(new EntityDefinition("student", "school_student", "Student", List.of(
                        FieldDefinition.titleField()
                ), Set.of(EntityCapability.CRUD, EntityCapability.REFERENCE)))
        );
    }

    private ModuleDefinition formulaInvoiceModule() {
        EntityDefinition invoice = new EntityDefinition(
                "invoice",
                "app_invoice",
                "Invoice",
                List.of(FieldDefinition.titleField().required())
        ).withCapabilities(EntityCapability.REFERENCE)
                .withFormulaRules(new EntityFormulaRuleDefinition("lineAmountCalc",
                        "SUM({lines.lineAmount} = {lines.quantity} * {lines.price})"));
        EntityDefinition line = new EntityDefinition(
                "invoice_line",
                "app_invoice_line",
                "Invoice Line",
                List.of(
                        FieldDefinition.string("invoiceId", "Invoice").column("invoice_id").length(64).required().indexed(),
                        FieldDefinition.decimal("quantity", "Quantity").precision(18, 2),
                        FieldDefinition.decimal("price", "Price").precision(18, 2),
                        FieldDefinition.decimal("lineAmount", "Line Amount").column("line_amount").precision(18, 2)
                )
        );
        return ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoice, line))
                .relations(List.of(EntityRelationDefinition.child("lines", "invoice", "invoice_line", "invoiceId")))
                .build();
    }

    private ModuleDefinition sortableInvoiceModule() {
        return ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), sortableInvoiceLineEntity()))
                .relations(List.of(EntityRelationDefinition.child("lines", "invoice", "invoice_line", "invoiceId")
                        .withAutoPopulate()
                        ))
                .build();
    }

    private ModuleDefinition writeProtectedForeignKeyInvoiceModule() {
        return ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), writeProtectedInvoiceLineEntity()))
                .relations(List.of(EntityRelationDefinition.child("lines", "invoice", "invoice_line", "invoiceId")))
                .build();
    }

    private ModuleDefinition manyReferenceInvoiceModule() {
        return ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of(EntityRelationDefinition.child("lines", "invoice", "invoice_line", "invoiceId")
                        .withAutoPopulate()
                        ))
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId", ReferenceTarget.of("sales.invoice", "invoice")).many()))
                .build();
    }

    private ModuleDefinition projectionOnlyInvoiceModule() {
        return ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of(EntityRelationDefinition.child("lines", "invoice", "invoice_line", "invoiceId")
                        .withAutoPopulate()
                        ))
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId", ReferenceTarget.of("sales.invoice", "invoice"))
                        .withProjection("title", "invoiceDisplayTitle")))
                .build();
    }

    private ModuleDefinition manyProjectionInvoiceModule() {
        return ModuleDefinition.builder(MODULE, "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of())
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId", ReferenceTarget.of("sales.invoice", "invoice"))
                        .many()
                        .withProjection("title", "invoiceDisplayTitle")))
                .build();
    }

    private EntityDefinition invoiceEntity() {
        return new EntityDefinition(
                "invoice",
                "app_invoice",
                "Invoice",
                List.of(FieldDefinition.titleField().required())
        ).withCapabilities(EntityCapability.REFERENCE);
    }

    private EntityDefinition invoiceLineEntity() {
        return new EntityDefinition(
                "invoice_line",
                "app_invoice_line",
                "Invoice Line",
                List.of(
                        FieldDefinition.string("invoiceId", "Invoice").column("invoice_id").length(64).required().indexed(),
                        FieldDefinition.titleField().required()
                )
        ).withCapabilities(EntityCapability.REFERENCE);
    }

    private EntityDefinition writeProtectedInvoiceLineEntity() {
        return new EntityDefinition(
                "invoice_line",
                "app_invoice_line",
                "Invoice Line",
                List.of(
                        FieldDefinition.string("invoiceId", "Invoice").column("invoice_id").length(64).required().indexed().writeProtected(),
                        FieldDefinition.titleField().required()
                )
        ).withCapabilities(EntityCapability.REFERENCE);
    }

    private EntityDefinition sortableInvoiceLineEntity() {
        return new EntityDefinition(
                "invoice_line",
                "app_invoice_line",
                "Invoice Line",
                List.of(
                        FieldDefinition.string("invoiceId", "Invoice").column("invoice_id").length(64).required().indexed(),
                        FieldDefinition.titleField().required(),
                        FieldDefinition.sortOrder()
                )
        ).withCapabilities(EntityCapability.REFERENCE, EntityCapability.SORT);
    }

    private Map<String, Object> invoiceRow() {
        return invoiceRow("I-001");
    }

    private Map<String, Object> invoiceRow(String title) {
        return Map.of(
                "id", "invoice-1",
                "tenant_id", "tenant-a",
                "title", title,
                "deleted", Boolean.FALSE,
                "version", 1
        );
    }

    private Map<String, Object> invoiceRowWithNullTitle() {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", "invoice-1");
        row.put("tenant_id", "tenant-a");
        row.put("title", null);
        row.put("deleted", Boolean.FALSE);
        row.put("version", 1);
        return row;
    }

    private Map<String, Object> lineRow() {
        return lineRow("L-001");
    }

    private Map<String, Object> lineRow(String title) {
        return Map.of(
                "id", "line-1",
                "tenant_id", "tenant-a",
                "invoice_id", "invoice-1",
                "title", title,
                "deleted", Boolean.FALSE,
                "version", 1
        );
    }

    private Map<String, Object> lineRow(String id, String title, Integer sortOrder) {
        return Map.of(
                "id", id,
                "tenant_id", "tenant-a",
                "invoice_id", "invoice-1",
                "title", title,
                "sort_order", sortOrder,
                "deleted", Boolean.FALSE,
                "version", 1
        );
    }

    private Map<String, Object> formulaLineRow(String id, int quantity, int price, int lineAmount) {
        return Map.of(
                "id", id,
                "tenant_id", "tenant-a",
                "invoice_id", "invoice-1",
                "quantity", BigDecimal.valueOf(quantity),
                "price", BigDecimal.valueOf(price),
                "line_amount", BigDecimal.valueOf(lineAmount),
                "deleted", Boolean.FALSE,
                "version", 1
        );
    }

    private void stubInvoiceAndLineRows(IDatabaseOperations<Object> operations) {
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (sql.contains("\"app_invoice_line\"") && sql.contains("\"invoice_id\" =")) {
                return containsParam(params, "invoice-1")
                        ? List.of(lineRow("line-1", "L-001", 1), lineRow("line-2", "L-002", 2))
                        : List.of();
            }
            if (sql.contains("\"app_invoice_line\"") && sql.contains("\"id\" =")) {
                if (containsParam(params, "line-1")) {
                    return List.of(lineRow("line-1", "L-001", 1));
                }
                if (containsParam(params, "line-2")) {
                    return List.of(lineRow("line-2", "L-002", 2));
                }
                if (containsParam(params, "line-foreign")) {
                    return List.of(lineRow("line-foreign", "Foreign", 1));
                }
                return List.of();
            }
            if (sql.contains("\"app_invoice\"") && sql.contains("\"id\" =")) {
                return containsParam(params, "invoice-1") ? List.of(invoiceRow()) : List.of();
            }
            return List.of();
        });
    }

    @SuppressWarnings("unchecked")
    private IDatabaseOperations<Object> operations() {
        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        when(operations.getDBInfo()).thenReturn(new DBInfo("POSTGRESQL").setName("muyun_test"));
        when(operations.getDefaultSchemaName()).thenReturn(SCHEMA);
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString())).thenReturn(1);
        return operations;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private static final class RecordingMutationCoordinator implements DynamicRecordMutationCoordinator {
        private final List<String> events = new ArrayList<>();

        private List<String> events() {
            return events;
        }

        @Override
        public void beforeRelationChildCreate(String moduleAlias,
                                              String parentEntityAlias,
                                              String relationCode,
                                              String childEntityAlias,
                                              DynamicRecord parent,
                                              DynamicRecord child) {
            events.add("beforeChildCreate:" + relationCode + ":" + childEntityAlias + ":"
                    + child.getValue("title") + ":" + child.getValue("invoiceId"));
        }

        @Override
        public void afterRelationChildCreate(String moduleAlias,
                                             String parentEntityAlias,
                                             String relationCode,
                                             String childEntityAlias,
                                             DynamicRecord parent,
                                             DynamicRecord child,
                                             String id) {
            events.add("afterChildCreate:" + relationCode + ":" + childEntityAlias + ":"
                    + child.getValue("title") + ":" + id);
        }

        @Override
        public void beforeRelationChildUpdate(String moduleAlias,
                                              String parentEntityAlias,
                                              String relationCode,
                                              String childEntityAlias,
                                              DynamicRecord parentBefore,
                                              DynamicRecord parentIncoming,
                                              DynamicRecord childBefore,
                                              DynamicRecord childIncoming) {
            events.add("beforeChildUpdate:" + relationCode + ":" + childBefore.getId() + ":"
                    + childIncoming.getValue("title"));
        }

        @Override
        public void afterRelationChildUpdate(String moduleAlias,
                                             String parentEntityAlias,
                                             String relationCode,
                                             String childEntityAlias,
                                             DynamicRecord parentBefore,
                                             DynamicRecord parentUpdated,
                                             DynamicRecord childBefore,
                                             DynamicRecord childUpdated) {
            events.add("afterChildUpdate:" + relationCode + ":" + childBefore.getId() + ":"
                    + childUpdated.getValue("title"));
        }

        @Override
        public void beforeRelationChildDelete(String moduleAlias,
                                              String parentEntityAlias,
                                              String relationCode,
                                              String childEntityAlias,
                                              DynamicRecord parentBefore,
                                              DynamicRecord childBefore) {
            events.add("beforeChildDelete:" + relationCode + ":" + childBefore.getId() + ":"
                    + childBefore.getValue("title"));
        }

        @Override
        public void afterRelationChildDelete(String moduleAlias,
                                             String parentEntityAlias,
                                             String relationCode,
                                             String childEntityAlias,
                                             DynamicRecord parentBefore,
                                             DynamicRecord childBefore) {
            events.add("afterChildDelete:" + relationCode + ":" + childBefore.getId() + ":"
                    + childBefore.getValue("title"));
        }
    }
}
