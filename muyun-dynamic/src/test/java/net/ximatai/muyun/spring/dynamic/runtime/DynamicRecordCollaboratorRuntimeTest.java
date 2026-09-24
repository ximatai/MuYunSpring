package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrityPolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import java.util.Optional;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Direct collaborator contracts for the dynamic-record facade decomposition.
 *
 * <p>The facade remains the public API. These tests deliberately instantiate its package-private
 * collaborators so permission, transaction failure and declared-relation failures cannot regress
 * behind a facade-only test suite.</p>
 */
class DynamicRecordCollaboratorRuntimeTest {
    private static final String MODULE = "sales.contract";
    private static final String WAREHOUSE_MODULE = "sales.warehouse";
    private static final String SCHEMA = "public";

    @Test
    void queryRuntimeRejectsUnauthorizedActionBeforeReadingRecords() {
        IDatabaseOperations<Object> operations = operations();
        ActionExecutionPolicyService denied = context -> {
            throw new PlatformException("query denied");
        };
        DynamicRecordRuntime runtime = runtime(operations, listActionModule(), DynamicActionTransactionOperator.none());
        DynamicRecordQueryRuntime queries = new DynamicRecordQueryRuntime(access(runtime, denied));

        assertThatThrownBy(() -> queries.pageForAction(MODULE, "contract", "recalculate",
                Criteria.of(), PageRequest.of(1, 10)))
                .isInstanceOf(PlatformException.class)
                .hasMessage("query denied");

        verify(operations, never()).query(anyString(), anyMap());
    }

    @Test
    void mutationRuntimeRejectsUnauthorizedBusinessCreateBeforePersistence() {
        IDatabaseOperations<Object> operations = operations();
        DynamicRecordRuntime runtime = runtime(operations, plainModule(), DynamicActionTransactionOperator.none());
        DynamicRecordMutationRuntime mutations = new DynamicRecordMutationRuntime(
                new DynamicRecordEventPublisher(RuntimeEventPublisher.noop()),
                access(runtime, context -> { throw new PlatformException("create denied"); }),
                DynamicRecordMutationCoordinator.NONE, null);
        DynamicRecord record = runtime.newRecord(MODULE, "contract").setValue("code", "C-001");

        assertThatThrownBy(() -> mutations.create(MODULE, "contract", record,
                RuntimeMutationSource.BUSINESS, "trace-1", Map.of()))
                .isInstanceOf(PlatformException.class)
                .hasMessage("create denied");

        verify(operations, never()).insertItem(anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    void mutationRuntimeDisablesReferencedTargetWithoutApplyingItsDeletionPolicy() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "warehouse-1", "version", 0, "enabled", true, "deleted", false)));
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString())).thenReturn(1);
        DynamicRecordRuntime runtime = runtime(operations, warehouseModule(), DynamicActionTransactionOperator.none())
                .register(restrictingPurchaseModule());
        DynamicRecordMutationRuntime mutations = new DynamicRecordMutationRuntime(
                new DynamicRecordEventPublisher(RuntimeEventPublisher.noop()),
                access(runtime, new AllowAllActionExecutionPolicyService()), DynamicRecordMutationCoordinator.NONE, null);

        try (var ignored = net.ximatai.muyun.spring.common.tenant.TenantContext.system("disable contract")) {
            assertThat(mutations.disable(WAREHOUSE_MODULE, "warehouse", "warehouse-1", 0,
                    RuntimeMutationSource.SYSTEM, "trace-1")).isEqualTo(1);
        }
        verify(operations).patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString());
        verify(operations, never()).row(anyString(), anyMap());
    }

    @Test
    void withdrawnModuleCannotLeaveStaleInboundGuardsAndReactivationAdvancesItsRevision() {
        var operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        var runtime = runtime(operations, warehouseModule(), DynamicActionTransactionOperator.none())
                .register(restrictingPurchaseModule());
        var target = ReferenceTarget.of(WAREHOUSE_MODULE, "warehouse");
        assertThatThrownBy(() -> runtime.validateReferenceTargetUnavailable(target, "warehouse-1"))
                .isInstanceOf(PlatformException.class);
        long previousRevision = runtime.registry().revision(MODULE);
        runtime.deactivate(MODULE);
        assertThat(runtime.registry().findModule(MODULE)).isEmpty();
        org.assertj.core.api.Assertions.assertThatCode(() -> runtime.validateReferenceTargetUnavailable(target, "warehouse-1"))
                .doesNotThrowAnyException();
        runtime.register(restrictingPurchaseModule());
        assertThat(runtime.registry().revision(MODULE)).isGreaterThan(previousRevision);
    }

    @Test
    void actionRuntimeRollsBackItsTransactionAndPreservesExecutionFailure() {
        IDatabaseOperations<Object> operations = operations();
        AtomicInteger transactions = new AtomicInteger();
        AtomicInteger rollbacks = new AtomicInteger();
        DynamicActionTransactionOperator transaction = (context, action) -> {
            transactions.incrementAndGet();
            try {
                return action.get();
            } catch (RuntimeException failure) {
                rollbacks.incrementAndGet();
                throw failure;
            }
        };
        DynamicActionExecutor failingExecutor = new DynamicActionExecutor() {
            @Override public String executorKey() { return "failing-export"; }
            @Override public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
                throw new IllegalStateException("executor failed");
            }
        };
        DynamicRecordRuntime runtime = DynamicRecordRuntime.builder(operations)
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(DynamicFieldValueValidator.NONE)
                .eventPublisher(RuntimeEventPublisher.noop())
                .actionExecutorRegistry(new DynamicActionExecutorRegistry(List.of(failingExecutor)))
                .actionTransactionOperator(transaction)
                .build()
                .register(listActionModule());
        var policy = new AllowAllActionExecutionPolicyService();
        var access = access(runtime, policy);
        var events = new DynamicRecordEventPublisher(RuntimeEventPublisher.noop());
        DynamicRecordActionRuntime actions = new DynamicRecordActionRuntime(runtime, access,
                new DynamicRecordQueryRuntime(access),
                new DynamicRecordMutationRuntime(events, access, DynamicRecordMutationCoordinator.NONE, null),
                events);

        assertThatThrownBy(() -> actions.executeAction(MODULE, "recalculate", DynamicActionExecutionRequest.empty()))
                .isInstanceOf(DynamicActionExecutionException.class)
                .hasMessage("executor failed")
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThat(transactions.get()).isEqualTo(1);
        assertThat(rollbacks.get()).isEqualTo(1);
    }

    @Test
    void relationRuntimeFailsForAnUndeclaredReferenceWithoutFallingBackToGenericLookup() {
        IDatabaseOperations<Object> operations = operations();
        DynamicRecordRuntime runtime = runtime(operations, plainModule(), DynamicActionTransactionOperator.none());
        var policy = new AllowAllActionExecutionPolicyService();
        var access = access(runtime, policy);
        DynamicRecordRelationRuntime relations = new DynamicRecordRelationRuntime(access,
                new DynamicRecordQueryRuntime(access));

        assertThatThrownBy(() -> relations.reference(MODULE, "contract", "customerId"))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("unknown dynamic reference");

        verify(operations, never()).query(anyString(), anyMap());
    }

    @Test
    void actionRuntimeRejectsUnauthorizedCreateBeforeCallingMutationRuntime() {
        var runtime = runtime(operations(), plainModule(), DynamicActionTransactionOperator.none());
        ActionExecutionPolicyService denied = context -> { throw new PlatformException("action denied"); };
        var access = access(runtime, denied);
        var mutations = mock(DynamicRecordMutationRuntime.class);
        var actions = new DynamicRecordActionRuntime(runtime, access,
                new DynamicRecordQueryRuntime(access), mutations,
                new DynamicRecordEventPublisher(RuntimeEventPublisher.noop()));

        assertThatThrownBy(() -> actions.executeAction(MODULE, "contract", "create",
                DynamicActionExecutionRequest.empty()))
                .isInstanceOf(PlatformException.class).hasMessage("action denied");
        org.mockito.Mockito.verifyNoInteractions(mutations);
    }

    @Test
    void relationReferenceFailureClosesItsCrossTenantReadScope() {
        var runtime = mock(DynamicRecordRuntime.class);
        var entity = mock(DynamicEntityService.class);
        when(runtime.entityService(MODULE, "contract")).thenReturn(entity);
        var policy = new AllowAllActionExecutionPolicyService();
        var scope = new AllowAllDataScopeCriteriaService() {
            @Override public DataScopeCriteriaResult resolveReadScope(String moduleAlias, ActionExecutionPolicy action,
                    Criteria criteria, Optional<CurrentUser> currentUser) {
                assertThat(action.actionCode()).isEqualTo("reference");
                return DataScopeCriteriaResult.crossTenantUnrestricted(criteria);
            }
        };
        var access = new DynamicRecordAccessContext(runtime, policy, scope);
        var relations = new DynamicRecordRelationRuntime(access, new DynamicRecordQueryRuntime(access));
        Criteria criteria = Criteria.of();
        PageRequest page = PageRequest.of(1, 10);
        when(entity.referenceOptions(criteria, page)).thenAnswer(invocation -> {
            assertThat(TenantContext.tenantFilterBypassed()).isTrue();
            throw new PlatformException("target read failed");
        });
        try (var ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> relations.referenceOptions(MODULE, "contract", criteria, page))
                    .isInstanceOf(PlatformException.class).hasMessage("target read failed");
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
    }

    private DynamicRecordAccessContext access(DynamicRecordRuntime runtime, ActionExecutionPolicyService policy) {
        return new DynamicRecordAccessContext(runtime, policy, new AllowAllDataScopeCriteriaService());
    }

    private DynamicRecordRuntime runtime(IDatabaseOperations<Object> operations, ModuleDefinition module,
                                         DynamicActionTransactionOperator transaction) {
        return DynamicRecordRuntime.builder(operations)
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(DynamicFieldValueValidator.NONE)
                .eventPublisher(RuntimeEventPublisher.noop())
                .actionTransactionOperator(transaction)
                .build()
                .register(module);
    }

    private ModuleDefinition plainModule() {
        return new ModuleDefinition(MODULE, "Contract", List.of(contractEntity()));
    }

    private ModuleDefinition listActionModule() {
        EntityActionDefinition export = new EntityActionDefinition("contract", "recalculate", "重算", true,
                EntityActionLevel.LIST, EntityActionCategory.CUSTOM, null, null, null, null,
                null, null, EntityActionExecutorType.SERVICE, "failing-export");
        return ModuleDefinition.builder(MODULE, "Contract")
                .entities(List.of(contractEntity()))
                .actions(List.of(export))
                .build();
    }

    private ModuleDefinition warehouseModule() {
        EntityDefinition warehouse = new EntityDefinition("warehouse", "app_warehouse", "Warehouse",
                List.of(
                        FieldDefinition.string("name", "Name").length(64).required(),
                        FieldDefinition.enabled()
                )).withCapabilities(EntityCapability.CRUD, EntityCapability.ENABLE);
        return new ModuleDefinition(WAREHOUSE_MODULE, "Warehouse", List.of(warehouse));
    }

    private ModuleDefinition restrictingPurchaseModule() {
        EntityDefinition purchase = new EntityDefinition("purchase", "app_purchase", "Purchase",
                List.of(FieldDefinition.string("warehouseId", "Warehouse").column("warehouse_id").length(32)));
        return ModuleDefinition.builder(MODULE, "Purchase")
                .entities(List.of(purchase))
                .references(List.of(EntityReferenceDefinition
                        .to("purchase", "warehouseId", ReferenceTarget.of(WAREHOUSE_MODULE, "warehouse"))
                        .withIntegrity(new ReferenceIntegrityPolicy(ReferenceTargetUnavailablePolicy.RESTRICT))))
                .build();
    }

    private EntityDefinition contractEntity() {
        return new EntityDefinition("contract", "app_contract", "Contract",
                List.of(FieldDefinition.string("code", "Code").length(64).required()));
    }

    @SuppressWarnings("unchecked")
    private IDatabaseOperations<Object> operations() {
        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        when(operations.getDBInfo()).thenReturn(new DBInfo("POSTGRESQL").setName("muyun_test"));
        when(operations.getDefaultSchemaName()).thenReturn(SCHEMA);
        return operations;
    }
}
