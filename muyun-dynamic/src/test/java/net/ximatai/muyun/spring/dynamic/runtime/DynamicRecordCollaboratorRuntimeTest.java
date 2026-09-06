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
        DynamicRecordQueryRuntime queries = new DynamicRecordQueryRuntime(runtime, denied,
                new AllowAllDataScopeCriteriaService());

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
        DynamicRecordMutationRuntime mutations = new DynamicRecordMutationRuntime(runtime,
                new DynamicRecordEventPublisher(RuntimeEventPublisher.noop()),
                context -> { throw new PlatformException("create denied"); },
                new AllowAllDataScopeCriteriaService(), DynamicRecordMutationCoordinator.NONE, null);
        DynamicRecord record = runtime.newRecord(MODULE, "contract").setValue("code", "C-001");

        assertThatThrownBy(() -> mutations.create(MODULE, "contract", record,
                RuntimeMutationSource.BUSINESS, "trace-1", Map.of()))
                .isInstanceOf(PlatformException.class)
                .hasMessage("create denied");

        verify(operations, never()).insertItem(anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    void mutationRuntimeRejectsDisablingTargetWithRestrictDynamicReferences() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "purchase-1",
                "warehouse_id", "warehouse-1",
                "version", 0,
                "deleted", false
        )));
        DynamicRecordRuntime runtime = runtime(operations, warehouseModule(), DynamicActionTransactionOperator.none())
                .register(restrictingPurchaseModule());
        DynamicRecordMutationRuntime mutations = new DynamicRecordMutationRuntime(runtime,
                new DynamicRecordEventPublisher(RuntimeEventPublisher.noop()),
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService(),
                new AllowAllDataScopeCriteriaService(), DynamicRecordMutationCoordinator.NONE, null);

        assertThatThrownBy(() -> mutations.disable(WAREHOUSE_MODULE, "warehouse", "warehouse-1", 0,
                RuntimeMutationSource.SYSTEM, "trace-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("cannot make reference target unavailable")
                .hasMessageContaining("sales.warehouse.warehouse");

        verify(operations, never()).patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString());
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
        DynamicRecordService facade = new DynamicRecordService(runtime);
        DynamicRecordActionRuntime actions = new DynamicRecordActionRuntime(facade, runtime,
                new DynamicRecordEventPublisher(RuntimeEventPublisher.noop()),
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService());

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
        DynamicRecordRelationRuntime relations = new DynamicRecordRelationRuntime(new DynamicRecordService(runtime));

        assertThatThrownBy(() -> relations.reference(MODULE, "contract", "customerId"))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("unknown dynamic reference");

        verify(operations, never()).query(anyString(), anyMap());
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
