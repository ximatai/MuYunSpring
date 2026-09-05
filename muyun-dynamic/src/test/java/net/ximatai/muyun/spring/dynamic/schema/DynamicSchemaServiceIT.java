package net.ximatai.muyun.spring.dynamic.schema;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.MigrationOptions;
import net.ximatai.muyun.database.core.orm.OrmException;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.database.core.orm.SqlRawCondition;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.deletion.DeletionContext;
import net.ximatai.muyun.spring.ability.deletion.DeletionMode;
import net.ximatai.muyun.spring.ability.deletion.DeletionNode;
import net.ximatai.muyun.spring.ability.reference.ReferenceDeletionGuard;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargets;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrityPolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityAssociationViewDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRefreshResult;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRuntimeRefresher;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityServiceTestFactory;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicQueryCondition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordDao;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = DynamicSchemaServiceIT.TestApplication.class)
class DynamicSchemaServiceIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.default-schema", () -> "public");
    }

    private final DynamicSchemaService schemaService;
    private final IDatabaseOperations<?> operations;
    private final DataSource dataSource;
    private final DynamicTransactionProbe transactionProbe;

    @Autowired
    DynamicSchemaServiceIT(DynamicSchemaService schemaService,
                           IDatabaseOperations<?> operations,
                           DataSource dataSource,
                           DynamicTransactionProbe transactionProbe) {
        this.schemaService = schemaService;
        this.operations = operations;
        this.dataSource = dataSource;
        this.transactionProbe = transactionProbe;
    }

    @Test
    void shouldCreateDynamicTableAndKeepSecondEnsureSchemaStable() throws Exception {
        EntityDefinition entity = new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required().unique(),
                        FieldDefinition.string("name", "Name").length(128).required(),
                        FieldDefinition.decimal("amount", "Amount").precision(18, 2),
                        FieldDefinition.timestamp("signedAt", "Signed At").column("signed_at").indexed()
                )
        );

        assertThat(schemaService.ensureTable(entity)).isTrue();
        TableShape firstShape;
        try (Connection connection = dataSource.getConnection()) {
            firstShape = tableShape(connection, "app_contract");
        }

        schemaService.ensureTable(entity);

        try (Connection connection = dataSource.getConnection()) {
            TableShape secondShape = tableShape(connection, "app_contract");
            assertThat(secondShape).isEqualTo(firstShape);
            assertThat(secondShape.columns())
                    .contains("id", "tenant_id", "version", "deleted", "created_by", "created_at", "updated_by", "updated_at",
                            "code", "name", "amount", "signed_at");
            assertThat(secondShape.primaryKeys()).containsExactly("id");
            assertThat(secondShape.uniqueIndexColumns()).contains(List.of("tenant_id", "code"));
        }
    }

    @Test
    void shouldRollbackSchemaEnsureInsideSpringTransaction() throws Exception {
        String tableName = "app_schema_tx_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        EntityDefinition entity = new EntityDefinition("schema_tx", tableName, "Schema Tx",
                List.of(FieldDefinition.string("code", "Code")));

        assertThatThrownBy(() -> transactionProbe.ensureTableThenFail(schemaService, entity))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("rollback schema ensure");

        try (Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement("""
                     select count(*) from information_schema.tables
                     where table_schema = 'public' and table_name = ?
                     """)) {
            statement.setString(1, tableName);
            try (java.sql.ResultSet result = statement.executeQuery()) {
                result.next();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    @Test
    void shouldApplyChildReferenceDeletionPoliciesThroughDynamicRecordServiceOnRealDatabase() {
        assertChildReferenceDeletionPolicy(ReferenceTargetUnavailablePolicy.CASCADE_DELETE);
        assertChildReferenceDeletionPolicy(ReferenceTargetUnavailablePolicy.RESTRICT);
        assertChildReferenceDeletionPolicy(ReferenceTargetUnavailablePolicy.PRESERVE_HISTORY);
    }

    @Test
    void shouldMoveOnlyRecordsInsideDynamicTreeActionCriteriaScopeOnRealDatabase() {
        List<RuntimeEvent> events = new ArrayList<>();
        DynamicRecordRuntime runtime = DynamicRecordRuntime.builder(operations)
                .eventPublisher(events::add).build();
        new DynamicModuleRuntimeRefresher(schemaService, runtime).refresh(invoiceModule());
        events.clear();
        DynamicRecordService service = new DynamicRecordService(runtime);
        DynamicEntityService invoices = runtime.entityService("sales.invoice", "invoice");
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (TenantContext.Scope ignored = TenantContext.use("tenant-tree-action-" + suffix)) {
            String parent = invoices.insert(runtime.newRecord("sales.invoice", "invoice").setValue("code", "P-" + suffix).setValue("title", "Parent"));
            String a1 = invoices.insert(runtime.newRecord("sales.invoice", "invoice").setValue("code", "A1-" + suffix).setValue("title", "First").setValue("parentId", parent));
            String a2 = invoices.insert(runtime.newRecord("sales.invoice", "invoice").setValue("code", "A2-" + suffix).setValue("title", "Second").setValue("parentId", parent));
            String b1 = invoices.insert(runtime.newRecord("sales.invoice", "invoice").setValue("code", "B1-" + suffix).setValue("title", "Outside").setValue("parentId", parent));
            // Exhaust the gap so TreeAbility must reorder the scoped sibling set.
            invoices.update(invoices.select(a2).setValue("sortOrder", Integer.MAX_VALUE));
            DynamicRecord bBefore = invoices.select(b1);
            List<String> before = invoices.children(parent).stream().map(DynamicRecord::getId).toList();
            Criteria scope = Criteria.of().in("code", List.of("P-" + suffix, "A1-" + suffix, "A2-" + suffix));

            service.moveInTreeFromAction("sales.invoice", "invoice", a1, a2, null, parent, scope, "tree-scope-it");

            assertThat(invoices.children(parent).stream().map(DynamicRecord::getId).toList())
                    .isNotEqualTo(before);
            assertThat(invoices.children(scope, parent)).extracting(DynamicRecord::getId).containsExactly(a2, a1);
            DynamicRecord bAfter = invoices.select(b1);
            assertThat(bAfter.getValue("sortOrder")).isEqualTo(bBefore.getValue("sortOrder"));
            assertThat(bAfter.getVersion()).isEqualTo(bBefore.getVersion());
            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.moduleAlias()).isEqualTo("sales.invoice");
                assertThat(event.recordId()).isEqualTo(a1);
                assertThat(event.traceId()).isEqualTo("tree-scope-it");
                assertThat(event.eventType().name()).isEqualTo("AFTER_UPDATE");
                assertThat(event.payload()).containsEntry("operation", "moveInTree");
            });
        }
    }

    @Test
    void shouldRejectEveryOutOfScopeDynamicTreeSortArgumentWithoutMutationOrEvent() {
        List<RuntimeEvent> events = new ArrayList<>();
        DynamicRecordRuntime runtime = DynamicRecordRuntime.builder(operations).eventPublisher(events::add).build();
        new DynamicModuleRuntimeRefresher(schemaService, runtime).refresh(invoiceModule());
        events.clear();
        DynamicRecordService service = new DynamicRecordService(runtime);
        DynamicEntityService invoices = runtime.entityService("sales.invoice", "invoice");
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (TenantContext.Scope ignored = TenantContext.use("tenant-tree-boundary-" + suffix)) {
            String parent = invoices.insert(runtime.newRecord("sales.invoice", "invoice").setValue("code", "P-" + suffix).setValue("title", "Parent"));
            String moving = invoices.insert(runtime.newRecord("sales.invoice", "invoice").setValue("code", "M-" + suffix).setValue("title", "Moving").setValue("parentId", parent));
            String previous = invoices.insert(runtime.newRecord("sales.invoice", "invoice").setValue("code", "N-" + suffix).setValue("title", "Neighbor").setValue("parentId", parent));
            String foreignParent = invoices.insert(runtime.newRecord("sales.invoice", "invoice").setValue("code", "FP-" + suffix).setValue("title", "Foreign parent"));
            String foreignMoving = invoices.insert(runtime.newRecord("sales.invoice", "invoice").setValue("code", "FM-" + suffix).setValue("title", "Foreign moving").setValue("parentId", foreignParent));
            String foreignPrevious = invoices.insert(runtime.newRecord("sales.invoice", "invoice").setValue("code", "FN-" + suffix).setValue("title", "Foreign previous").setValue("parentId", foreignParent));
            List<String> snapshot = treeSnapshot(invoices);
            Criteria scope = Criteria.of().in("code", List.of("P-" + suffix, "M-" + suffix, "N-" + suffix));
            List<Runnable> attempts = List.of(
                    () -> service.moveInTreeFromAction("sales.invoice", "invoice", foreignMoving, previous, null, parent, scope, "boundary"),
                    () -> service.moveInTreeFromAction("sales.invoice", "invoice", moving, foreignPrevious, null, parent, scope, "boundary"),
                    () -> service.moveInTreeFromAction("sales.invoice", "invoice", moving, previous, foreignMoving, parent, scope, "boundary"),
                    () -> service.moveInTreeFromAction("sales.invoice", "invoice", moving, null, null, foreignParent, scope, "boundary"));
            for (Runnable attempt : attempts) {
                assertThatThrownBy(attempt::run).isInstanceOf(PlatformException.class).hasMessageContaining("outside tree sort scope");
                assertThat(treeSnapshot(invoices)).isEqualTo(snapshot);
                assertThat(events).isEmpty();
            }
        }
    }

    private List<String> treeSnapshot(DynamicEntityService invoices) {
        return invoices.list(Criteria.of(), PageRequest.of(1, Integer.MAX_VALUE), Sort.asc("id"))
                .stream().map(record -> record.getId() + ":" + record.getValue("parentId") + ":"
                        + record.getValue("sortOrder") + ":" + record.getVersion()).toList();
    }

    @Test
    void shouldRejectDynamicTreeSortWhenSortDataScopeDeniesRecords() {
        List<RuntimeEvent> events = new ArrayList<>();
        DynamicRecordRuntime runtime = DynamicRecordRuntime.builder(operations).eventPublisher(events::add).build();
        new DynamicModuleRuntimeRefresher(schemaService, runtime).refresh(invoiceModule());
        events.clear();
        DynamicEntityService invoices = runtime.entityService("sales.invoice", "invoice");
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (TenantContext.Scope ignored = TenantContext.use("tenant-sort-data-scope-" + suffix)) {
            String parent = invoices.insert(runtime.newRecord("sales.invoice", "invoice").setValue("code", "DSP-" + suffix).setValue("title", "Parent"));
            String moving = invoices.insert(runtime.newRecord("sales.invoice", "invoice").setValue("code", "DSM-" + suffix).setValue("title", "Moving").setValue("parentId", parent));
            String neighbor = invoices.insert(runtime.newRecord("sales.invoice", "invoice").setValue("code", "DSN-" + suffix).setValue("title", "Neighbor").setValue("parentId", parent));
            List<String> before = treeSnapshot(invoices);
            DataScopeCriteriaService denySort = new DataScopeCriteriaService() {
                @Override public DataScopeCriteriaResult resolveReadScope(String module, String action, Criteria criteria, java.util.Optional<CurrentUser> user) {
                    return DataScopeCriteriaResult.restricted(Criteria.of().eq("code", "never"));
                }
                @Override public DataScopeCriteriaResult resolveReadScope(String module, ActionExecutionPolicy policy, Criteria criteria, java.util.Optional<CurrentUser> user) {
                    return DataScopeCriteriaResult.restricted(Criteria.of().eq("code", "never"));
                }
            };
            DynamicRecordService scoped = new DynamicRecordService(runtime, new AllowAllActionExecutionPolicyService(), denySort);
            assertThatThrownBy(() -> scoped.moveInTreeFromAction("sales.invoice", "invoice", moving,
                    neighbor, null, parent, Criteria.of().in("code", List.of("DSP-" + suffix, "DSM-" + suffix, "DSN-" + suffix)), "data-scope"))
                    .isInstanceOf(PlatformException.class).hasMessageContaining("record data permission denied");
            assertThat(treeSnapshot(invoices)).isEqualTo(before);
            assertThat(events).isEmpty();
        }
    }

    private void assertChildReferenceDeletionPolicy(ReferenceTargetUnavailablePolicy policy) {
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String moduleAlias = "sales.deletion_" + suffix;
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations);
        new DynamicModuleRuntimeRefresher(schemaService, runtime).refresh(
                deletionPolicyModule(moduleAlias, "app_invoice_deletion_" + suffix,
                        "app_invoice_line_deletion_" + suffix, policy));
        DynamicRecordService service = new DynamicRecordService(runtime);

        PlatformAbilityRuntime.configureReferenceDeletionGuard(dynamicReferenceGuard(runtime));
        try (TenantContext.Scope ignored = TenantContext.use("tenant-deletion-" + suffix)) {
            String invoiceId = service.create(moduleAlias, "invoice",
                    service.newRecord(moduleAlias, "invoice")
                            .setValue("code", "INV-" + policy)
                            .setValue("title", "Invoice " + policy));
            String lineId = service.create(moduleAlias, "invoice_line",
                    service.newRecord(moduleAlias, "invoice_line")
                            .setValue("title", "Line " + policy)
                            .setValue("invoiceId", invoiceId));

            DynamicRecord persistedLine = service.select(moduleAlias, "invoice_line", lineId);
            assertThat(persistedLine.getValue("invoiceId")).isEqualTo(invoiceId);
            assertThat(runtime.entityService(moduleAlias, "invoice_line").collectReferenceIdsByTarget(persistedLine))
                    .containsEntry(ReferenceTarget.of(moduleAlias, "invoice"), Set.of(invoiceId));
            assertThat(service.associationViewPage(moduleAlias, "invoice_line", lineId, "invoice",
                    Criteria.of(), PageRequest.of(1, 10)).getRecords())
                    .extracting(DynamicRecord::getId)
                    .containsExactly(invoiceId);

            if (policy == ReferenceTargetUnavailablePolicy.RESTRICT) {
                assertThatThrownBy(() -> service.delete(moduleAlias, "invoice", invoiceId))
                        .isInstanceOf(PlatformException.class)
                        .hasMessageContaining("cannot make reference target unavailable");
                assertThat(service.select(moduleAlias, "invoice", invoiceId)).isNotNull();
                assertThat(service.select(moduleAlias, "invoice_line", lineId)).isNotNull();
                return;
            }

            assertThat(service.delete(moduleAlias, "invoice", invoiceId)).isEqualTo(1);
            assertThat(service.select(moduleAlias, "invoice", invoiceId)).isNull();
            if (policy == ReferenceTargetUnavailablePolicy.CASCADE_DELETE) {
                assertThat(service.select(moduleAlias, "invoice_line", lineId)).isNull();
                assertThat(service.selectIgnoreSoftDelete(moduleAlias, "invoice_line", lineId)).isNotNull();
            } else {
                assertThat(service.select(moduleAlias, "invoice_line", lineId))
                        .extracting(record -> record.getValue("invoiceId"))
                        .isEqualTo(invoiceId);
            }
        } finally {
            PlatformAbilityRuntime.resetReferenceDeletionGuard();
        }
    }

    private ReferenceDeletionGuard dynamicReferenceGuard(DynamicRecordRuntime runtime) {
        return new ReferenceDeletionGuard() {
            @Override
            public void validateTargetUnavailable(CrudAbility<?> targetAbility, EntityContract target) {
                runtime.validateReferenceTargetDeletion(ReferenceTargets.of(targetAbility), target.getId());
            }

            @Override
            public void cascadeTargetUnavailable(CrudAbility<?> targetAbility,
                                                 EntityContract target,
                                                 DeletionContext context,
                                                 DeletionNode node,
                                                 DeletionMode mode) {
                runtime.cascadeReferenceTargetUnavailable(ReferenceTargets.of(targetAbility), target.getId(), context, node);
            }
        };
    }

    @Test
    void shouldRunDynamicAbilitiesOnRealDatabase() {
        ModuleDefinition module = invoiceModule();
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations);
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime);
        refresher.refresh(module);

        DynamicEntityService invoiceService = runtime.entityService("sales.invoice", "invoice");
        DynamicEntityService lineService = runtime.entityService("sales.invoice", "invoice_line");
        String rootId;
        String firstChildId;
        String secondChildId;
        String lineId;

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            DynamicRecord root = runtime.newRecord("sales.invoice", "invoice")
                    .setValue("code", "INV-ROOT")
                    .setValue("title", "Root invoice");
            rootId = invoiceService.insert(root);

            DynamicRecord firstChild = runtime.newRecord("sales.invoice", "invoice")
                    .setValue("code", "INV-CHILD-1")
                    .setValue("title", "First child")
                    .setValue("parentId", rootId);
            firstChildId = invoiceService.insert(firstChild);

            DynamicRecord secondChild = runtime.newRecord("sales.invoice", "invoice")
                    .setValue("code", "INV-CHILD-2")
                    .setValue("title", "Second child")
                    .setValue("parentId", rootId);
            secondChildId = invoiceService.insert(secondChild);

            String anotherRootId = invoiceService.insert(runtime.newRecord("sales.invoice", "invoice")
                    .setValue("code", "INV-OTHER-ROOT")
                    .setValue("title", "Other root"));
            String anotherChildId = invoiceService.insert(runtime.newRecord("sales.invoice", "invoice")
                    .setValue("code", "INV-OTHER-CHILD")
                    .setValue("title", "Other child")
                    .setValue("parentId", anotherRootId));
            List<String> rootOrderBeforeRejectedMove = invoiceService.sortedList(Criteria.of().eq("parentId", rootId))
                    .stream().map(DynamicRecord::getId).toList();
            List<String> otherOrderBeforeRejectedMove = invoiceService.sortedList(Criteria.of().eq("parentId", anotherRootId))
                    .stream().map(DynamicRecord::getId).toList();
            assertThatThrownBy(() -> invoiceService.moveBefore(firstChildId, anotherChildId))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("same parent");
            assertThatThrownBy(() -> invoiceService.reorder(List.of(firstChildId)))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("complete scope");
            assertThat(invoiceService.sortedList(Criteria.of().eq("parentId", rootId)).stream()
                    .map(DynamicRecord::getId).toList()).isEqualTo(rootOrderBeforeRejectedMove);
            assertThat(invoiceService.sortedList(Criteria.of().eq("parentId", anotherRootId)).stream()
                    .map(DynamicRecord::getId).toList()).isEqualTo(otherOrderBeforeRejectedMove);

            invoiceService.reorder(List.of(secondChildId, firstChildId));
            assertThat(invoiceService.sortedList(Criteria.of().eq("parentId", rootId)).stream().map(DynamicRecord::getId))
                    .containsExactly(secondChildId, firstChildId);
            assertThat(invoiceService.children(rootId).stream().map(record -> record.getValue("title")))
                    .containsExactly("Second child", "First child");
            assertThat(invoiceService.title(firstChildId)).isEqualTo("First child");
            assertThat(invoiceService.referenceOptions(Criteria.of().eq("parentId", rootId), PageRequest.of(1, 10)).getRecords())
                    .extracting("id")
                    .containsExactlyInAnyOrder(secondChildId, firstChildId);
            assertThat(invoiceService.delete(secondChildId)).isEqualTo(1);
            assertThat(invoiceService.select(secondChildId)).isNull();
            assertThat(invoiceService.selectIgnoreSoftDelete(secondChildId)).isNotNull();
            assertThat(invoiceService.sortedList(Criteria.of().eq("parentId", rootId)).stream().map(DynamicRecord::getId))
                    .containsExactly(firstChildId);
            assertThat(invoiceService.children(rootId).stream().map(DynamicRecord::getId))
                    .containsExactly(firstChildId);
            assertThat(invoiceService.children(secondChildId)).isEmpty();
            assertThat(invoiceService.descendantIds(secondChildId)).isEmpty();
            assertThat(invoiceService.title(secondChildId)).isNull();
            assertThat(invoiceService.referenceOptions(Criteria.of().eq("parentId", rootId), PageRequest.of(1, 10)).getRecords())
                    .extracting("id")
                    .containsExactly(firstChildId);

            DynamicRecord invoiceWithLine = runtime.newRecord("sales.invoice", "invoice")
                    .setValue("code", "INV-LINE")
                    .setValue("title", "Invoice with line");
            DynamicRecord line = runtime.newRecord("sales.invoice", "invoice_line")
                    .setValue("title", "Line 001");
            DynamicRecord removedLine = runtime.newRecord("sales.invoice", "invoice_line")
                    .setValue("title", "Line 002");
            invoiceWithLine.setChildren("lines", List.of(line, removedLine));
            String invoiceWithLineId = invoiceService.insert(invoiceWithLine);
            lineId = line.getId();
            String removedLineId = removedLine.getId();

            DynamicRecord loadedLine = invoiceService.select(invoiceWithLineId).getChildren("lines").getFirst();
            assertThat(loadedLine)
                    .extracting(child -> child.getValue("title"), child -> child.getValue("invoiceId"))
                    .containsExactly("Line 001", invoiceWithLineId);
            assertThat(lineService.collectReferenceIdsByTarget(loadedLine))
                    .containsEntry(ReferenceTarget.of("sales.invoice", "invoice"), java.util.Set.of(invoiceWithLineId));
            assertThat(lineService.select(lineId))
                    .extracting(child -> child.getValue("invoiceId"))
                    .isEqualTo(invoiceWithLineId);

            DynamicRecord retainedLine = runtime.newRecord("sales.invoice", "invoice_line")
                    .setValue("title", "Line 001 updated");
            retainedLine.setId(lineId);
            retainedLine.setVersion(0);
            DynamicRecord newLine = runtime.newRecord("sales.invoice", "invoice_line")
                    .setValue("title", "Line 003");
            DynamicRecord invoiceUpdate = runtime.newRecord("sales.invoice", "invoice")
                    .setValue("code", "INV-LINE")
                    .setValue("title", "Invoice with line updated");
            invoiceUpdate.setId(invoiceWithLineId);
            invoiceUpdate.setVersion(0);
            invoiceUpdate.setChildren("lines", List.of(retainedLine, newLine));
            assertThat(invoiceService.update(invoiceUpdate)).isEqualTo(1);

            assertThat(invoiceService.select(invoiceWithLineId).getChildren("lines"))
                    .extracting(child -> child.getValue("title"))
                    .containsExactlyInAnyOrder("Line 001 updated", "Line 003");
            assertThat(lineService.select(lineId).getValue("title")).isEqualTo("Line 001 updated");
            assertThat(lineService.select(removedLineId)).isNull();
            assertThat(lineService.selectIgnoreSoftDelete(removedLineId)).isNotNull();

            assertThatThrownBy(() -> invoiceService.insert(runtime.newRecord("sales.invoice", "invoice")
                    .setValue("code", "INV-ROOT")
                    .setValue("title", "Duplicate in tenant A")))
                    .isInstanceOf(PlatformException.class)
                    .extracting(error -> ((PlatformException) error).code())
                    .isEqualTo(PlatformErrorCodes.CONFLICT_UNIQUE);

            assertThat(invoiceService.delete(invoiceWithLineId)).isEqualTo(1);
            assertThat(lineService.select(lineId)).isNull();
            assertThat(lineService.selectIgnoreSoftDelete(lineId)).isNotNull();
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            assertThat(invoiceService.select(rootId)).isNull();
            assertThat(invoiceService.insert(runtime.newRecord("sales.invoice", "invoice")
                    .setValue("code", "INV-ROOT")
                    .setValue("title", "Same code in tenant B"))).hasSize(32);
        }
    }

    @Test
    void shouldRollbackDynamicParentChildInsertWhenTransactionFails() {
        DynamicRecordRuntime runtime = refreshedInvoiceRuntime();
        DynamicEntityService invoiceService = runtime.entityService("sales.invoice", "invoice");
        DynamicEntityService lineService = runtime.entityService("sales.invoice", "invoice_line");
        String code = "INV-TX-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String lineTitle = "Line TX " + code;
        String tenantId = "tenant-tx-" + code;

        try (TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            invoiceService.selectAllWithCache();
            lineService.selectAllWithCache();

            assertThatThrownBy(() -> transactionProbe.insertInvoiceWithLineThenFail(invoiceService, lineService, runtime, code, lineTitle))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("rollback dynamic insert");

            assertThat(invoiceService.count(Criteria.of().eq("code", code))).isZero();
            assertThat(lineService.count(Criteria.of().eq("title", lineTitle))).isZero();
            assertThat(invoiceService.selectAllWithCache())
                    .extracting(record -> record.getValue("code"))
                    .doesNotContain(code);
            assertThat(lineService.selectAllWithCache())
                    .extracting(record -> record.getValue("title"))
                    .doesNotContain(lineTitle);
        }
    }

    @Test
    void shouldRollbackDynamicParentChildReplaceWhenTransactionFails() {
        DynamicRecordRuntime runtime = refreshedInvoiceRuntime();
        DynamicEntityService invoiceService = runtime.entityService("sales.invoice", "invoice");
        DynamicEntityService lineService = runtime.entityService("sales.invoice", "invoice_line");
        String code = "INV-TX-REPLACE-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String tenantId = "tenant-tx-" + code;

        try (TenantContext.Scope ignored = TenantContext.use(tenantId)) {
            DynamicRecord invoice = runtime.newRecord("sales.invoice", "invoice")
                    .setValue("code", code)
                    .setValue("title", "Before replace");
            DynamicRecord retainedLine = runtime.newRecord("sales.invoice", "invoice_line")
                    .setValue("title", "Retained before " + code);
            DynamicRecord removedLine = runtime.newRecord("sales.invoice", "invoice_line")
                    .setValue("title", "Removed before " + code);
            invoice.setChildren("lines", List.of(retainedLine, removedLine));
            String invoiceId = invoiceService.insert(invoice);
            String retainedLineId = retainedLine.getId();
            String removedLineId = removedLine.getId();
            invoiceService.select(invoiceId);
            invoiceService.selectAllWithCache();
            lineService.select(retainedLineId);
            lineService.select(removedLineId);
            lineService.selectAllWithCache();

            assertThatThrownBy(() -> transactionProbe.replaceInvoiceLinesThenFail(
                    invoiceService,
                    lineService,
                    runtime,
                    invoiceId,
                    retainedLineId,
                    removedLineId,
                    code
            ))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("rollback dynamic replace");

            DynamicRecord loaded = invoiceService.select(invoiceId);
            assertThat(loaded.getValue("title")).isEqualTo("Before replace");
            assertThat(loaded.getChildren("lines"))
                    .extracting(child -> child.getValue("title"))
                    .containsExactlyInAnyOrder("Retained before " + code, "Removed before " + code);
            assertThat(lineService.select(retainedLineId).getValue("title")).isEqualTo("Retained before " + code);
            assertThat(lineService.select(removedLineId)).isNotNull();
            assertThat(lineService.count(Criteria.of().eq("title", "New after " + code))).isZero();
            assertThat(invoiceService.selectAllWithCache())
                    .extracting(record -> record.getValue("title"))
                    .doesNotContain("After replace");
            assertThat(lineService.selectAllWithCache())
                    .extracting(record -> record.getValue("title"))
                    .contains("Retained before " + code, "Removed before " + code)
                    .doesNotContain("Retained after " + code, "New after " + code);
        }
    }

    @Test
    void shouldRunDynamicRecordMinimalDataAccessLoopOnRealDatabase() {
        EntityDefinition entity = entity("app_contract_record_it");
        schemaService.ensureTable(entity);
        DynamicRecordDao dao = new DynamicRecordDao(operations, entity);
        DynamicEntityService entityService = DynamicEntityServiceTestFactory.forDataAccess(dao, "sales.contract");

        DynamicRecord record = new DynamicRecord(entity)
                .setValue("code", "C-IT-001")
                .setValue("name", "Integration Contract")
                .setValue("amount", BigDecimal.valueOf(1234, 2));

        String id = entityService.insert(record);

        assertThat(entityService.select(id).getValue("code")).isEqualTo("C-IT-001");
        assertThat(entityService.pageQuery(Criteria.of().eq("code", "C-IT-001"), PageRequest.of(1, 10), Sort.asc("name")).getRecords())
                .hasSize(1);
        assertThat(entityService.pageQuery(Criteria.of().eq("code", "C-IT-001"), PageRequest.of(1, 10)).getTotal())
                .isEqualTo(1);
        assertThat(entityService.count(Criteria.of().eq("code", "C-IT-001"))).isEqualTo(1);

        record.setValue("name", "Updated Contract");
        entityService.update(record);
        assertThat(entityService.select(id).getVersion()).isEqualTo(1);
        assertThat(entityService.select(id).getValue("name")).isEqualTo("Updated Contract");

        DynamicRecord staleUpdate = new DynamicRecord(entity)
                .setValue("name", "Stale Contract");
        staleUpdate.setId(id);
        staleUpdate.setVersion(0);
        assertThatThrownBy(() -> entityService.update(staleUpdate))
                .isInstanceOf(OptimisticLockException.class);

        DynamicRecord staleDelete = new DynamicRecord(entity);
        staleDelete.setId(id);
        staleDelete.setVersion(0);
        assertThatThrownBy(() -> entityService.delete(staleDelete))
                .isInstanceOf(OptimisticLockException.class);

        assertThat(entityService.delete(id)).isEqualTo(1);
        assertThat(entityService.select(id)).isNull();
        assertThat(entityService.count(Criteria.of().eq("code", "C-IT-001"))).isZero();
        assertThat(dao.count(Criteria.of().eq("code", "C-IT-001"))).isEqualTo(1);
    }

    @Test
    void shouldRunComplexCriteriaContractOnRealDatabase() {
        EntityDefinition entity = entity("app_contract_criteria_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        schemaService.ensureTable(entity);
        DynamicEntityService service = DynamicEntityServiceTestFactory.forDataAccess(
                new DynamicRecordDao(operations, entity), "sales.contract.criteria");

        try (TenantContext.Scope ignored = TenantContext.use("tenant-criteria")) {
            insertContract(service, entity, "C-CR-001", "Alpha", "2026-01-01T00:00:00Z", "10.00");
            insertContract(service, entity, "C-CR-002", "Beta", "2026-01-02T00:00:00Z", "20.00");
            insertContract(service, entity, "C-CR-003", "Gamma", "2026-01-03T00:00:00Z", "15.00");
            String deletedId = insertContract(service, entity, "C-CR-004", "Deleted", "2026-01-04T00:00:00Z", "15.00");
            insertContract(service, entity, "C-CR-005", "Blocked", "2026-01-05T00:00:00Z", "15.00");
            insertContract(service, entity, "C-CR-006", "Out of Range", "2026-01-06T00:00:00Z", "99.00");
            insertContract(service, entity, "C-CR-007", "No Signed At", null, "15.00");
            service.delete(deletedId);
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-other")) {
            insertContract(service, entity, "C-CR-008", "Other Tenant", "2026-01-08T00:00:00Z", "15.00");
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-criteria")) {
            Criteria criteria = Criteria.of()
                    .in("code", List.of("C-CR-001", "C-CR-002", "C-CR-003", "C-CR-004", "C-CR-005", "C-CR-006", "C-CR-007", "C-CR-008"))
                    .notIn("code", List.of("C-CR-003"))
                    .between("amount", new BigDecimal("10.00"), new BigDecimal("30.00"))
                    .isNotNull("signedAt")
                    .raw(SqlRawCondition.of("\"name\" <> :blocked", Map.of("blocked", "Blocked")));

            assertThat(service.pageQuery(criteria, PageRequest.of(1, 1), Sort.desc("amount")).getRecords())
                    .extracting(record -> record.getValue("code"))
                    .containsExactly("C-CR-002");
            assertThat(service.count(criteria)).isEqualTo(2);
            assertThat(service.pageQuery(Criteria.of().in("code", List.of()), PageRequest.of(1, 10)).getRecords())
                    .isEmpty();
            assertThat(service.pageQuery(Criteria.of().notIn("code", List.of()), PageRequest.of(1, 10)).getTotal())
                    .isEqualTo(6);

            assertThatThrownBy(() -> service.count(Criteria.of().eq("missingField", "x")))
                    .isInstanceOf(OrmException.class)
                    .hasMessageContaining("missingField")
                    .extracting("code")
                    .isEqualTo(OrmException.Code.INVALID_CRITERIA);
            assertThatThrownBy(() -> service.count(Criteria.of().raw(SqlRawCondition.of("\"name\" = ?", Map.of()))))
                    .isInstanceOf(OrmException.class)
                    .extracting("code")
                    .isEqualTo(OrmException.Code.INVALID_CRITERIA);
        }
    }

    @Test
    void shouldRefreshModuleThenRunDynamicRecordOnRealDatabase() {
        ModuleDefinition module = new ModuleDefinition(
                "sales.contract",
                "Contract",
                List.of(entity("app_contract_refresh_it"))
        );
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations);
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime);

        DynamicModuleRefreshResult result = refresher.refresh(module);
        DynamicRecordService recordService = new DynamicRecordService(runtime);
        DynamicRecord record = recordService.newRecord("sales.contract", "contract")
                .setValue("code", "C-REFRESH-001")
                .setValue("name", "Refreshed Contract")
                .setValue("amount", BigDecimal.valueOf(5678, 2));

        String id = recordService.create("sales.contract", "contract", record);

        assertThat(result.changed()).isTrue();
        assertThat(result.migrations()).containsKey("contract");
        assertThat(recordService.select("sales.contract", "contract", id).getValue("code")).isEqualTo("C-REFRESH-001");
        assertThat(recordService.count("sales.contract", "contract", Criteria.of().eq("code", "C-REFRESH-001"))).isEqualTo(1);
    }

    @Test
    void shouldQueryDynamicJsonCollectionFieldOnRealDatabase() {
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String tableName = "app_contract_json_" + suffix;
        String moduleAlias = "sales.json_" + suffix;
        ModuleDefinition module = new ModuleDefinition(
                moduleAlias,
                "Json Contract",
                List.of(new EntityDefinition(
                        "contract",
                        tableName,
                        "Contract",
                        List.of(
                                FieldDefinition.string("code", "Code").length(64).required().unique(),
                                FieldDefinition.of("tags", FieldType.JSON, "Tags")
                                        .jsonSet()
                                        .queryable(DynamicQueryOperator.CONTAINS, Set.of(
                                                DynamicQueryOperator.CONTAINS,
                                                DynamicQueryOperator.CONTAINS_ANY,
                                                DynamicQueryOperator.CONTAINS_ALL,
                                                DynamicQueryOperator.EMPTY,
                                                DynamicQueryOperator.NOT_EMPTY
                                        ))
                        )
                ))
        );
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations);
        new DynamicModuleRuntimeRefresher(schemaService, runtime).refresh(module);
        DynamicRecordService recordService = new DynamicRecordService(runtime);
        DynamicEntityOperations records = recordService.entity(moduleAlias, "contract");

        try (TenantContext.Scope ignored = TenantContext.use("tenant-json")) {
            records.create(records.newRecord()
                    .setValue("code", "C-JSON-001")
                    .setValue("tags", List.of("vip", "paid")));
            records.create(records.newRecord()
                    .setValue("code", "C-JSON-002")
                    .setValue("tags", List.of("trial")));
            records.create(records.newRecord()
                    .setValue("code", "C-JSON-003")
                    .setValue("tags", List.of()));

            assertThat(records.list(records.queryCriteria(List.of(
                                    DynamicQueryCondition.of("tags", DynamicQueryOperator.CONTAINS, "vip")
                            )), PageRequest.of(1, 10)))
                    .extracting(record -> record.getValue("code"))
                    .containsExactly("C-JSON-001");
            assertThat(records.list(records.queryCriteria(List.of(
                                    DynamicQueryCondition.of("tags", DynamicQueryOperator.CONTAINS_ANY, "vip", "trial")
                            )), PageRequest.of(1, 10), Sort.asc("code")))
                    .extracting(record -> record.getValue("code"))
                    .containsExactly("C-JSON-001", "C-JSON-002");
            assertThat(records.list(records.queryCriteria(List.of(
                                    DynamicQueryCondition.of("tags", DynamicQueryOperator.CONTAINS_ALL, "vip", "paid")
                            )), PageRequest.of(1, 10)))
                    .extracting(record -> record.getValue("code"))
                    .containsExactly("C-JSON-001");
            assertThat(records.list(records.queryCriteria(List.of(
                                    new DynamicQueryCondition("tags", DynamicQueryOperator.EMPTY, List.of())
                            )), PageRequest.of(1, 10)))
                    .extracting(record -> record.getValue("code"))
                    .containsExactly("C-JSON-003");
            assertThat(records.list(records.queryCriteria(List.of(
                                    new DynamicQueryCondition("tags", DynamicQueryOperator.NOT_EMPTY, List.of())
                            )), PageRequest.of(1, 10), Sort.asc("code")))
                    .extracting(record -> record.getValue("code"))
                    .containsExactly("C-JSON-001", "C-JSON-002");
        }
    }

    @Test
    void shouldRunDynamicStableFacadeAbilitiesOnRealDatabase() {
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String moduleAlias = "sales.facade_" + suffix;
        ModuleDefinition module = new ModuleDefinition(
                moduleAlias,
                "Facade Contract",
                List.of(facadeContractEntity("app_contract_facade_" + suffix))
        );
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations);
        new DynamicModuleRuntimeRefresher(schemaService, runtime).refresh(module);
        DynamicRecordService recordService = new DynamicRecordService(runtime);

        String activeId;
        String disabledId;
        String deletedId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-facade-a")) {
            activeId = recordService.create(moduleAlias, "contract", runtime.newRecord(moduleAlias, "contract")
                    .setValue("code", "C-FACADE-001")
                    .setValue("title", "Active Contract"));
            disabledId = recordService.create(moduleAlias, "contract", runtime.newRecord(moduleAlias, "contract")
                    .setValue("code", "C-FACADE-002")
                    .setValue("title", "Disabled Contract"));
            deletedId = recordService.create(moduleAlias, "contract", runtime.newRecord(moduleAlias, "contract")
                    .setValue("code", "C-FACADE-003")
                    .setValue("title", "Deleted Contract"));

            assertThat(recordService.isEnabled(moduleAlias, "contract", activeId)).isTrue();
            assertThat(recordService.disable(moduleAlias, "contract", disabledId)).isEqualTo(1);
            assertThat(recordService.isEnabled(moduleAlias, "contract", disabledId)).isFalse();
            assertThat(recordService.enable(moduleAlias, "contract", disabledId)).isEqualTo(1);
            assertThat(recordService.disable(moduleAlias, "contract", disabledId)).isEqualTo(1);
            assertThat(recordService.delete(moduleAlias, "contract", deletedId)).isEqualTo(1);
        }

        String tenantBId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-facade-b")) {
            tenantBId = recordService.create(moduleAlias, "contract", runtime.newRecord(moduleAlias, "contract")
                    .setValue("code", "C-FACADE-004")
                    .setValue("title", "Other Tenant Contract"));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-facade-a")) {
            Criteria enabledActive = recordService.enabledCriteria(moduleAlias, "contract", Criteria.of().eq("code", "C-FACADE-001"));
            assertThat(recordService.list(moduleAlias, "contract", enabledActive, PageRequest.of(1, 10)))
                    .extracting(record -> record.getValue("title"))
                    .containsExactly("Active Contract");

            Criteria enabledAll = recordService.enabledCriteria(moduleAlias, "contract", Criteria.of());
            assertThat(recordService.list(moduleAlias, "contract", enabledAll, PageRequest.of(1, 10), Sort.asc("code")))
                    .extracting(record -> record.getValue("code"))
                    .containsExactly("C-FACADE-001");
            assertThat(recordService.isEnabled(moduleAlias, "contract", deletedId)).isFalse();
            assertThat(recordService.enable(moduleAlias, "contract", deletedId)).isZero();
            assertThat(recordService.disable(moduleAlias, "contract", tenantBId)).isZero();
            assertThat(recordService.isEnabled(moduleAlias, "contract", tenantBId)).isFalse();

            assertThat(recordService.projections(
                    moduleAlias,
                    "contract",
                    List.of(disabledId, activeId, deletedId, tenantBId),
                    List.of("code", "title")
            ))
                    .containsExactly(
                            Map.entry(disabledId, Map.of("code", "C-FACADE-002", "title", "Disabled Contract")),
                            Map.entry(activeId, Map.of("code", "C-FACADE-001", "title", "Active Contract"))
                    );
        }
    }

    @Test
    void shouldPreviewAndRefreshDynamicModuleSchemaEvolutionOnRealDatabase() throws Exception {
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String tableName = "app_contract_evolve_" + suffix;
        String moduleAlias = "sales.evolve_" + suffix;
        ModuleDefinition initialModule = new ModuleDefinition(
                moduleAlias,
                "Contract",
                List.of(new EntityDefinition(
                        "contract",
                        tableName,
                        "Contract",
                        List.of(FieldDefinition.string("code", "Code").length(64).required().unique())
                ))
        );
        ModuleDefinition evolvedModule = new ModuleDefinition(
                moduleAlias,
                "Contract",
                List.of(new EntityDefinition(
                        "contract",
                        tableName,
                        "Contract",
                        List.of(
                                FieldDefinition.string("code", "Code").length(64).required().unique(),
                                FieldDefinition.string("name", "Name").length(128),
                                FieldDefinition.decimal("amount", "Amount").precision(18, 2)
                        )
                ))
        );
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations);
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime);
        DynamicRecordService recordService = new DynamicRecordService(runtime);

        refresher.refresh(initialModule);
        String firstId = recordService.create(moduleAlias, "contract", runtime.newRecord(moduleAlias, "contract")
                .setValue("code", "C-EVOLVE-001"));

        DynamicModuleRefreshResult dryRun = refresher.previewRefresh(evolvedModule);

        assertThat(dryRun.changed()).isTrue();
        assertThat(dryRun.dryRun()).isTrue();
        assertThat(dryRun.statementsByEntity().get("contract"))
                .anyMatch(sql -> sql.contains(" add ") && sql.contains("\"name\""))
                .anyMatch(sql -> sql.contains(" add ") && sql.contains("\"amount\""));
        assertThat(runtime.registry().requireEntity(moduleAlias, "contract").fields())
                .extracting(FieldDefinition::fieldName)
                .containsExactly("code");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(columns(connection, tableName)).doesNotContain("name", "amount");
        }

        DynamicModuleRefreshResult refreshed = refresher.refresh(evolvedModule);

        assertThat(refreshed.changed()).isTrue();
        assertThat(runtime.registry().requireEntity(moduleAlias, "contract").fields())
                .extracting(FieldDefinition::fieldName)
                .containsExactly("code", "name", "amount");
        String secondId = recordService.create(moduleAlias, "contract", runtime.newRecord(moduleAlias, "contract")
                .setValue("code", "C-EVOLVE-002")
                .setValue("name", "Evolved Contract")
                .setValue("amount", BigDecimal.valueOf(4567, 2)));
        assertThat(recordService.select(moduleAlias, "contract", firstId).getValue("name")).isNull();
        assertThat((BigDecimal) recordService.select(moduleAlias, "contract", secondId).getValue("amount"))
                .isEqualByComparingTo(BigDecimal.valueOf(4567, 2));
        try (Connection connection = dataSource.getConnection()) {
            assertThat(columns(connection, tableName)).contains("name", "amount");
        }

        ModuleDefinition removedFieldModule = new ModuleDefinition(
                moduleAlias,
                "Contract",
                List.of(new EntityDefinition(
                        "contract",
                        tableName,
                        "Contract",
                        List.of(
                                FieldDefinition.string("code", "Code").length(64).required().unique(),
                                FieldDefinition.decimal("amount", "Amount").precision(18, 2)
                        )
                ))
        );

        DynamicModuleRefreshResult dropDryRun = refresher.previewRefresh(removedFieldModule);

        assertThat(dropDryRun.dryRun()).isTrue();
        assertThat(dropDryRun.hasNonAdditiveChanges()).isTrue();
        assertThat(dropDryRun.statementsByEntity().get("contract"))
                .anyMatch(sql -> sql.contains("drop column \"name\""));
        assertThat(runtime.registry().requireEntity(moduleAlias, "contract").fields())
                .extracting(FieldDefinition::fieldName)
                .containsExactly("code", "name", "amount");
        assertThatThrownBy(() -> refresher.refresh(removedFieldModule, MigrationOptions.strict()))
                .isInstanceOfSatisfying(DynamicSchemaMigrationException.class, exception -> {
                    assertThat(exception.failedEntityAlias()).isEqualTo("contract");
                    assertThat(exception.completedMigrations()).isEmpty();
                    assertThat(exception.getCause()).isInstanceOfSatisfying(OrmException.class,
                            cause -> assertThat(cause.getCode()).isEqualTo(OrmException.Code.STRICT_MIGRATION_REJECTED));
                });
        assertThat(runtime.registry().requireEntity(moduleAlias, "contract").fields())
                .extracting(FieldDefinition::fieldName)
                .containsExactly("code", "name", "amount");

        DynamicModuleRefreshResult dropRefreshed = refresher.refresh(removedFieldModule);

        assertThat(dropRefreshed.migrations().get("contract").hasNonAdditiveChanges()).isTrue();
        assertThat(runtime.registry().requireEntity(moduleAlias, "contract").fields())
                .extracting(FieldDefinition::fieldName)
                .containsExactly("code", "amount");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(columns(connection, tableName)).doesNotContain("name");
        }
    }

    @Test
    void shouldSupportDryRunAndStrictDynamicSchemaMigration() throws Exception {
        EntityDefinition dryRunEntity = entity("app_contract_dry_run_it");

        var dryRun = schemaService.ensureTable(dryRunEntity, MigrationOptions.dryRun());

        assertThat(dryRun.isDryRun()).isTrue();
        assertThat(dryRun.isChanged()).isTrue();
        try (Connection connection = dataSource.getConnection()) {
            assertThat(tableExists(connection, "app_contract_dry_run_it")).isFalse();
        }

        EntityDefinition looseEntity = entity("app_contract_strict_it", false);
        EntityDefinition strictEntity = entity("app_contract_strict_it", true);
        schemaService.ensureTable(looseEntity);

        assertThatThrownBy(() -> schemaService.ensureTable(strictEntity, MigrationOptions.strict()))
                .isInstanceOf(OrmException.class)
                .extracting("code")
                .isEqualTo(OrmException.Code.STRICT_MIGRATION_REJECTED);
    }

    private EntityDefinition entity(String tableName) {
        return entity(tableName, true);
    }

    private EntityDefinition entity(String tableName, boolean nameRequired) {
        FieldDefinition name = FieldDefinition.string("name", "Name").length(128);
        if (nameRequired) {
            name = name.required();
        }
        return new EntityDefinition(
                "contract",
                tableName,
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required().unique(),
                        name,
                        FieldDefinition.decimal("amount", "Amount").precision(18, 2),
                        FieldDefinition.timestamp("signedAt", "Signed At").column("signed_at").indexed()
                )
        );
    }

    private String insertContract(DynamicEntityService service,
                                  EntityDefinition entity,
                                  String code,
                                  String name,
                                  String signedAt,
                                  String amount) {
        DynamicRecord record = new DynamicRecord(entity)
                .setValue("code", code)
                .setValue("name", name)
                .setValue("amount", new BigDecimal(amount));
        if (signedAt != null) {
            record.setValue("signedAt", Instant.parse(signedAt));
        }
        return service.insert(record);
    }

    private DynamicRecordRuntime refreshedInvoiceRuntime() {
        ModuleDefinition module = invoiceModule();
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations);
        new DynamicModuleRuntimeRefresher(schemaService, runtime).refresh(module);
        return runtime;
    }

    private List<String> columns(Connection connection) throws Exception {
        return columns(connection, "app_contract");
    }

    private List<String> columns(Connection connection, String tableName) throws Exception {
        try (var columns = connection.getMetaData().getColumns(null, "public", tableName, null)) {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            while (columns.next()) {
                names.add(columns.getString("COLUMN_NAME"));
            }
            return names;
        }
    }

    private List<String> primaryKeys(Connection connection) throws Exception {
        return primaryKeys(connection, "app_contract");
    }

    private List<String> primaryKeys(Connection connection, String tableName) throws Exception {
        try (var keys = connection.getMetaData().getPrimaryKeys(null, "public", tableName)) {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            while (keys.next()) {
                names.add(keys.getString("COLUMN_NAME"));
            }
            return names;
        }
    }

    private List<List<String>> uniqueIndexColumns(Connection connection, String tableName) throws Exception {
        try (var indexes = connection.getMetaData().getIndexInfo(null, "public", tableName, true, false)) {
            Map<String, List<String>> columnsByIndex = new LinkedHashMap<>();
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                String column = indexes.getString("COLUMN_NAME");
                if (name != null && column != null) {
                    columnsByIndex.computeIfAbsent(name, ignored -> new ArrayList<>()).add(column);
                }
            }
            return new ArrayList<>(columnsByIndex.values());
        }
    }

    private TableShape tableShape(Connection connection, String tableName) throws Exception {
        return new TableShape(
                columns(connection, tableName),
                primaryKeys(connection, tableName),
                uniqueIndexColumns(connection, tableName)
        );
    }

    private record TableShape(List<String> columns, List<String> primaryKeys, List<List<String>> uniqueIndexColumns) {
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception {
        try (var tables = connection.getMetaData().getTables(null, "public", tableName, null)) {
            return tables.next();
        }
    }

    private ModuleDefinition invoiceModule() {
        return ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of(EntityRelationDefinition.child("lines", "invoice", "invoice_line", "invoiceId")
                        .withAutoPopulate()
                        ))
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId", ReferenceTarget.of("sales.invoice", "invoice"))
                        .withIntegrity(new ReferenceIntegrityPolicy(ReferenceTargetUnavailablePolicy.CASCADE_DELETE))))
                .build();
    }

    private ModuleDefinition deletionPolicyModule(String moduleAlias,
                                                  String invoiceTable,
                                                  String lineTable,
                                                  ReferenceTargetUnavailablePolicy policy) {
        return ModuleDefinition.builder(moduleAlias, "Deletion policy invoice")
                .entities(List.of(
                        new EntityDefinition("invoice", invoiceTable, "Invoice", List.of(
                                FieldDefinition.string("code", "Code").required().unique(),
                                FieldDefinition.titleField().required()
                        )).withCapabilities(EntityCapability.CRUD, EntityCapability.REFERENCE),
                        new EntityDefinition("invoice_line", lineTable, "Invoice line", List.of(
                                FieldDefinition.string("invoiceId", "Invoice")
                                        .column("invoice_id").length(64).required().indexed(),
                                FieldDefinition.titleField().required()
                        )).withCapabilities(EntityCapability.CRUD, EntityCapability.REFERENCE)
                ))
                .relations(List.of(EntityRelationDefinition.child("lines", "invoice", "invoice_line", "invoiceId")))
                .references(List.of(EntityReferenceDefinition.to("invoice_line", "invoiceId",
                                ReferenceTarget.of(moduleAlias, "invoice"))
                        .withRuntimeConfig("code", "title", null, null, Set.of())
                        .withIntegrity(new ReferenceIntegrityPolicy(policy))))
                .associationViews(List.of(EntityAssociationViewDefinition.reference(
                        "invoice", "invoice_line", moduleAlias, "invoice", "invoiceId")))
                .build();
    }

    private EntityDefinition invoiceEntity() {
        return new EntityDefinition(
                "invoice",
                "app_invoice_ability_it",
                "Invoice",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required().unique(),
                        FieldDefinition.titleField().required(),
                        FieldDefinition.parentId(),
                        FieldDefinition.sortOrder()
                )
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.TREE, EntityCapability.SORT,
                EntityCapability.REFERENCE, EntityCapability.DATA_SCOPE);
    }

    private EntityDefinition invoiceLineEntity() {
        return new EntityDefinition(
                "invoice_line",
                "app_invoice_line_ability_it",
                "Invoice Line",
                List.of(
                        FieldDefinition.string("invoiceId", "Invoice").column("invoice_id").length(64).required().indexed(),
                        FieldDefinition.titleField().required()
                )
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.REFERENCE);
    }

    private EntityDefinition facadeContractEntity(String tableName) {
        return new EntityDefinition(
                "contract",
                tableName,
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required().unique(),
                        FieldDefinition.titleField().required(),
                        FieldDefinition.enabled()
                )
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.REFERENCE, EntityCapability.ENABLE);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableTransactionManagement
    static class TestApplication {

        @Bean
        DataSource dataSource() {
            return org.springframework.boot.jdbc.DataSourceBuilder.create()
                    .url(postgres.getJdbcUrl())
                    .username(postgres.getUsername())
                    .password(postgres.getPassword())
                    .driverClassName(postgres.getDriverClassName())
                    .build();
        }

        @Bean
        DynamicSchemaService dynamicSchemaService(IDatabaseOperations<?> operations) {
            return new DynamicSchemaService(operations);
        }

        @Bean
        DynamicTransactionProbe dynamicTransactionProbe() {
            return new DynamicTransactionProbe();
        }
    }

    static class DynamicTransactionProbe {

        @Transactional
        public void ensureTableThenFail(DynamicSchemaService schemaService, EntityDefinition entity) {
            schemaService.ensureTable(entity);
            throw new RuntimeException("rollback schema ensure");
        }

        @Transactional
        public void insertInvoiceWithLineThenFail(DynamicEntityService invoiceService,
                                                  DynamicEntityService lineService,
                                                  DynamicRecordRuntime runtime,
                                                  String code,
                                                  String lineTitle) {
            DynamicRecord invoice = runtime.newRecord("sales.invoice", "invoice")
                    .setValue("code", code)
                    .setValue("title", "Rollback insert");
            DynamicRecord line = runtime.newRecord("sales.invoice", "invoice_line")
                    .setValue("title", lineTitle);
            invoice.setChildren("lines", List.of(line));
            String invoiceId = invoiceService.insert(invoice);

            DynamicRecord loadedInvoice = invoiceService.select(invoiceId);
            DynamicRecord loadedLine = lineService.select(line.getId());
            assertThat(loadedInvoice).isNotNull();
            assertThat(loadedLine).isNotNull();
            assertThat(loadedLine.getValue("invoiceId")).isEqualTo(invoiceId);
            assertThat(invoiceService.selectAllWithCache())
                    .extracting(record -> record.getValue("code"))
                    .contains(code);
            assertThat(lineService.selectAllWithCache())
                    .extracting(record -> record.getValue("title"))
                    .contains(lineTitle);
            throw new RuntimeException("rollback dynamic insert");
        }

        @Transactional
        public void replaceInvoiceLinesThenFail(DynamicEntityService invoiceService,
                                                DynamicEntityService lineService,
                                                DynamicRecordRuntime runtime,
                                                String invoiceId,
                                                String retainedLineId,
                                                String removedLineId,
                                                String code) {
            DynamicRecord currentInvoice = invoiceService.select(invoiceId);
            DynamicRecord currentRetainedLine = lineService.select(retainedLineId);

            DynamicRecord retainedLine = runtime.newRecord("sales.invoice", "invoice_line")
                    .setValue("title", "Retained after " + code);
            retainedLine.setId(retainedLineId);
            retainedLine.setVersion(currentRetainedLine.getVersion());
            DynamicRecord newLine = runtime.newRecord("sales.invoice", "invoice_line")
                    .setValue("title", "New after " + code);
            DynamicRecord invoice = runtime.newRecord("sales.invoice", "invoice")
                    .setValue("code", code)
                    .setValue("title", "After replace");
            invoice.setId(invoiceId);
            invoice.setVersion(currentInvoice.getVersion());
            invoice.setChildren("lines", List.of(retainedLine, newLine));
            invoiceService.update(invoice);

            assertThat(invoiceService.select(invoiceId).getValue("title")).isEqualTo("After replace");
            assertThat(lineService.select(retainedLineId).getValue("title")).isEqualTo("Retained after " + code);
            assertThat(lineService.select(newLine.getId())).isNotNull();
            assertThat(lineService.select(removedLineId)).isNull();
            assertThat(lineService.selectIgnoreSoftDelete(removedLineId)).isNotNull();
            assertThat(invoiceService.selectAllWithCache())
                    .extracting(record -> record.getValue("title"))
                    .contains("After replace");
            assertThat(lineService.selectAllWithCache())
                    .extracting(record -> record.getValue("title"))
                    .contains("Retained after " + code, "New after " + code)
                    .doesNotContain("Removed before " + code);
            throw new RuntimeException("rollback dynamic replace");
        }
    }
}
