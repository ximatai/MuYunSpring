package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicDeletionRecoveryContractTest {
    @Test
    void shouldJournalDynamicEntityAliasAndRestoreThroughDynamicRuntime() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(
                List.of(record(false)), List.of(record(true)));
        TestMemoryDao<DeletionOperation> operationDao = new TestMemoryDao<>();
        DeletionLogService logService = new DeletionLogService(operationDao, new TestMemoryDao<>());
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations).register(module());
        DynamicRecordService records = new DynamicRecordService(runtime);
        DynamicEntityService entity = runtime.entityService("sales.contract", "contract");
        PlatformAbilityRuntime.configureDeletionLifecycleListener(new DeletionLogLifecycleListener(logService));
        try {
            assertThat(entity.delete("contract-1")).isEqualTo(1);
        } finally {
            PlatformAbilityRuntime.resetDeletionLifecycleListener();
        }
        DeletionOperation operation = operationDao.query(Criteria.of(), PageRequest.of(1, 1)).getFirst();
        DeletionEntry sourceEntry = logService.operationEntries(operation.getId()).getFirst();

        assertThat(sourceEntry.getResourceModuleAlias()).isEqualTo("sales.contract");
        assertThat(sourceEntry.getResourceEntityAlias()).isEqualTo("contract");

        RestoreReport report = new SoftDeleteRestoreCoordinator(logService, new DeletionRecoveryExecutor(logService), List.of(
                new DynamicDeletionRecoveryResourceResolver(Optional.of(records))))
                .restore(operation.getId());

        assertThat(report.entries()).singleElement()
                .extracting(result -> result.status())
                .isEqualTo(RestoreEntryResult.Status.RESTORED);
    }

    @SuppressWarnings("unchecked")
    private IDatabaseOperations<Object> operations() {
        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        when(operations.getDBInfo()).thenReturn(new DBInfo("POSTGRESQL").setName("muyun_test"));
        when(operations.getDefaultSchemaName()).thenReturn("public");
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), eq("id"))).thenReturn(1);
        return operations;
    }

    private ModuleDefinition module() {
        return new ModuleDefinition("sales.contract", "Contract", List.of(new EntityDefinition(
                "contract", "app_contract", "Contract", List.of(FieldDefinition.string("code", "Code")),
                Set.of(EntityCapability.CRUD))));
    }

    private Map<String, Object> record(boolean deleted) {
        return Map.of("id", "contract-1", "code", "C-001", "deleted", deleted, "version", 0);
    }
}
