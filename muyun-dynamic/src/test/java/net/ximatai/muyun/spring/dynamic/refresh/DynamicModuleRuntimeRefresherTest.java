package net.ximatai.muyun.spring.dynamic.refresh;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.database.core.orm.MigrationChange;
import net.ximatai.muyun.database.core.orm.MigrationOptions;
import net.ximatai.muyun.database.core.orm.MigrationResult;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicFieldValueValidator;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicModuleRegistry;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicModuleRuntimeRefresherTest {
    @Test
    void shouldEnsureSchemaThenRegisterRuntimeModule() {
        RecordingSchemaService schemaService = new RecordingSchemaService(false);
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations());
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime);

        DynamicModuleRefreshResult result = refresher.refresh(contractModule());

        assertThat(result.changed()).isTrue();
        assertThat(result.migrations()).containsKey("contract");
        assertThat(schemaService.lastOptions).isNull();
        assertThat(schemaService.ensuredEntities).containsExactly("contract");
        assertThat(runtime.registry().requireEntity("sales.contract", "contract").tableName())
                .isEqualTo("app_contract");
    }

    @Test
    void shouldPassExplicitMigrationOptionsThroughRefresh() {
        RecordingSchemaService schemaService = new RecordingSchemaService(false);
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations());
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime);
        MigrationOptions options = MigrationOptions.strict();

        refresher.refresh(contractModule(), options);

        assertThat(schemaService.lastOptions).isSameAs(options);
    }

    @Test
    void shouldActivateAlreadyEnsuredModuleWithoutDdlOrRefreshEvent() {
        RecordingSchemaService schemaService = new RecordingSchemaService(false);
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordRuntime runtime = runtime(events);
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime);

        DynamicModuleRefreshResult result = refresher.activateNow(contractModule());

        assertThat(result.migrations()).isEmpty();
        assertThat(schemaService.ensuredEntities).isEmpty();
        assertThat(events.events()).isEmpty();
        assertThat(runtime.registry().requireEntity("sales.contract", "contract").tableName())
                .isEqualTo("app_contract");
    }

    @Test
    void shouldEmitModuleRefreshEventAfterRuntimeRefresh() {
        RecordingSchemaService schemaService = new RecordingSchemaService(false);
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordRuntime runtime = runtime(events);
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime);

        refresher.refresh(contractModule());

        assertThat(events.events()).hasSize(1);
        RuntimeEvent event = events.events().getFirst();
        assertThat(event.eventType()).isEqualTo(RuntimeEventType.MODULE_REFRESHED);
        assertThat(event.moduleAlias()).isEqualTo("sales.contract");
        assertThat(event.entityAlias()).isNull();
        assertThat(event.systemContext()).isTrue();
        assertThat(event.systemReason()).isEqualTo("dynamic module refresh");
        assertThat(event.mutationSource()).isEqualTo(RuntimeMutationSource.SYSTEM);
        assertThat(event.payload()).containsEntry("changed", Boolean.TRUE)
                .containsEntry("nonAdditiveChanges", Boolean.FALSE);
        assertThat(event.payload().get("entities")).isEqualTo(List.of(Map.of(
                "entityAlias", "contract",
                "changed", Boolean.TRUE,
                "dryRun", Boolean.FALSE,
                "nonAdditiveChanges", Boolean.FALSE,
                "statements", List.of(),
                "changes", List.of()
        )));
    }

    @Test
    void shouldNotEmitModuleRefreshEventForPreview() {
        RecordingSchemaService schemaService = new RecordingSchemaService(true);
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime(events));

        refresher.previewRefresh(contractModule());

        assertThat(events.events()).isEmpty();
    }

    @Test
    void shouldKeepSystemContextOnModuleRefreshEvent() {
        RecordingSchemaService schemaService = new RecordingSchemaService(false);
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime(events));

        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            refresher.refresh(contractModule());
        }

        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.systemContext()).isTrue();
                    assertThat(event.systemReason()).isEqualTo("test system context");
                });
    }

    @Test
    void shouldNotRefreshRegistryForDryRun() {
        RecordingSchemaService schemaService = new RecordingSchemaService(true);
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations());
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime);

        DynamicModuleRefreshResult result = refresher.previewRefresh(contractModule());

        assertThat(result.dryRun()).isTrue();
        assertThat(result.migrations().get("contract").isDryRun()).isTrue();
        assertThatThrownBy(() -> runtime.entityService("sales.contract", "contract"))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("unknown module alias");
    }

    @Test
    void shouldKeepExistingRuntimeDefinitionWhenPreviewRefreshingEvolution() {
        RecordingSchemaService schemaService = new RecordingSchemaService(true);
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations())
                .register(contractModule());
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime);

        DynamicModuleRefreshResult result = refresher.previewRefresh(evolvedContractModule());

        assertThat(result.dryRun()).isTrue();
        assertThat(result.migrations().get("contract").isDryRun()).isTrue();
        assertThat(schemaService.previousModules.get("sales.contract")).isEqualTo(contractModule());
        assertThat(runtime.registry().requireEntity("sales.contract", "contract").fields())
                .extracting(FieldDefinition::fieldName)
                .containsExactly("code", "amount");
    }

    @Test
    void shouldReplaceRuntimeDefinitionAfterSchemaEvolutionSucceeds() {
        RecordingSchemaService schemaService = new RecordingSchemaService(false);
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations())
                .register(contractModule());
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime);

        refresher.refresh(evolvedContractModule());

        assertThat(schemaService.ensuredEntities).containsExactly("contract");
        assertThat(schemaService.previousModules.get("sales.contract")).isEqualTo(contractModule());
        assertThat(runtime.registry().requireEntity("sales.contract", "contract").fields())
                .extracting(FieldDefinition::fieldName)
                .containsExactly("code", "amount", "title");
    }

    @Test
    void shouldKeepExistingRuntimeDefinitionWhenSchemaEvolutionFails() {
        FailingSchemaService schemaService = new FailingSchemaService();
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations())
                .register(contractModule());
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime);

        assertThatThrownBy(() -> refresher.refresh(evolvedContractModule()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema failed");
        assertThat(runtime.registry().requireEntity("sales.contract", "contract").fields())
                .extracting(FieldDefinition::fieldName)
                .containsExactly("code", "amount");
    }

    @Test
    void shouldExposeRefreshResultSummaryWithoutTraversingMigrations() {
        RecordingSchemaService schemaService = new RecordingSchemaService(true, true);
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations());
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime);

        DynamicModuleRefreshResult result = refresher.previewRefresh(contractModule());

        assertThat(result.changed()).isTrue();
        assertThat(result.dryRun()).isTrue();
        assertThat(result.hasNonAdditiveChanges()).isTrue();
        assertThat(result.statementsByEntity()).containsEntry("contract", List.of("alter table app_contract drop column name"));
        assertThat(result.changesByEntity().get("contract"))
                .singleElement()
                .satisfies(change -> {
                    assertThat(change.getType()).isEqualTo(MigrationChange.Type.DROP_COLUMN);
                    assertThat(change.getTarget()).isEqualTo("app_contract.name");
                    assertThat(change.isNonAdditive()).isTrue();
                });
    }

    @Test
    void shouldKeepPreviewSideEffectFreeEvenWhenSchemaReturnsNoMigrations() {
        EmptySchemaService schemaService = new EmptySchemaService();
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations());
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime);

        DynamicModuleRefreshResult result = refresher.previewRefresh(emptyModule());

        assertThat(result.dryRun()).isTrue();
        assertThat(result.changed()).isFalse();
        assertThat(schemaService.lastOptions.isDryRun()).isTrue();
        assertThat(runtime.registry().findModule("sales.empty")).isEmpty();
    }

    @Test
    void shouldValidateBeforeRegisteringModule() {
        RecordingSchemaService schemaService = new RecordingSchemaService(false);
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations());
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime);
        ModuleDefinition invalid = new ModuleDefinition(
                "sales.contract",
                "Contract",
                List.of(
                        entity("contract", "app_contract"),
                        entity("contract_copy", "app_contract")
                )
        );

        assertThatThrownBy(() -> refresher.refresh(invalid))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("duplicate table name");
        assertThat(schemaService.ensuredEntities).isEmpty();
        assertThat(runtime.registry().findModule("sales.contract")).isEmpty();
    }

    @Test
    void shouldRegisterFirstRefreshThroughRefresher() {
        RecordingSchemaService schemaService = new RecordingSchemaService(false);
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations());
        DynamicModuleRuntimeRefresher refresher = new DynamicModuleRuntimeRefresher(schemaService, runtime);

        refresher.refresh(contractModule());

        assertThat(runtime.registry().findModule("sales.contract")).isPresent();
        assertThat(schemaService.ensuredEntities).containsExactly("contract");
    }

    @SuppressWarnings("unchecked")
    private IDatabaseOperations<Object> operations() {
        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        when(operations.getDBInfo()).thenReturn(new DBInfo("POSTGRESQL").setName("muyun_test"));
        when(operations.getDefaultSchemaName()).thenReturn("public");
        return operations;
    }

    private ModuleDefinition contractModule() {
        return new ModuleDefinition("sales.contract", "Contract", List.of(entity("contract", "app_contract")));
    }

    private DynamicRecordRuntime runtime(RuntimeEventPublisher eventPublisher) {
        return DynamicRecordRuntime.builder(operations())
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(DynamicFieldValueValidator.NONE)
                .eventPublisher(eventPublisher)
                .build();
    }

    private ModuleDefinition emptyModule() {
        return new ModuleDefinition("sales.empty", "Empty", List.of());
    }

    private ModuleDefinition evolvedContractModule() {
        return new ModuleDefinition(
                "sales.contract",
                "Contract",
                List.of(new EntityDefinition(
                        "contract",
                        "app_contract",
                        "Contract",
                        List.of(
                                FieldDefinition.string("code", "Code").length(64).required(),
                                FieldDefinition.decimal("amount", "Amount").precision(18, 2),
                                FieldDefinition.titleField()
                        )
                ).withCapabilities(net.ximatai.muyun.spring.common.platform.EntityCapability.CRUD,
                        net.ximatai.muyun.spring.common.platform.EntityCapability.REFERENCE))
        );
    }

    private EntityDefinition entity(String code, String tableName) {
        return new EntityDefinition(
                code,
                tableName,
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.decimal("amount", "Amount").precision(18, 2)
                )
        );
    }

    private static class RecordingSchemaService extends DynamicSchemaService {
        private final boolean dryRun;
        private final List<String> ensuredEntities = new java.util.ArrayList<>();
        private final Map<String, ModuleDefinition> previousModules = new LinkedHashMap<>();
        private MigrationOptions lastOptions;

        RecordingSchemaService(boolean dryRun) {
            this(dryRun, false);
        }

        RecordingSchemaService(boolean dryRun, boolean nonAdditive) {
            super(null);
            this.dryRun = dryRun;
            this.nonAdditive = nonAdditive;
        }

        private final boolean nonAdditive;

        @Override
        public Map<String, MigrationResult> ensureModule(ModuleDefinition module,
                                                         ModuleDefinition previousModule,
                                                         MigrationOptions options) {
            new net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator().validate(module);
            lastOptions = options;
            if (previousModule != null) {
                previousModules.put(module.moduleAlias(), previousModule);
            }
            Map<String, MigrationResult> results = new LinkedHashMap<>();
            for (EntityDefinition entity : module.entities()) {
                ensuredEntities.add(entity.alias());
                List<String> statements = nonAdditive ? List.of("alter table app_contract drop column name") : List.of();
                List<MigrationChange> changes = nonAdditive
                        ? List.of(MigrationChange.nonAdditive(
                                MigrationChange.Type.DROP_COLUMN,
                                "app_contract.name",
                                "alter table app_contract drop column name"
                        ))
                        : List.of();
                results.put(entity.alias(), new MigrationResult(
                        true,
                        dryRun,
                        nonAdditive,
                        statements,
                        changes
                ));
            }
            return results;
        }
    }

    private static class FailingSchemaService extends DynamicSchemaService {
        FailingSchemaService() {
            super(null);
        }

        @Override
        public Map<String, MigrationResult> ensureModule(ModuleDefinition module,
                                                         ModuleDefinition previousModule,
                                                         MigrationOptions options) {
            throw new IllegalStateException("schema failed");
        }
    }

    private static class EmptySchemaService extends DynamicSchemaService {
        private MigrationOptions lastOptions;

        EmptySchemaService() {
            super(null);
        }

        @Override
        public Map<String, MigrationResult> ensureModule(ModuleDefinition module,
                                                         ModuleDefinition previousModule,
                                                         MigrationOptions options) {
            lastOptions = options;
            return Map.of();
        }
    }

    private static final class CollectingRuntimeEventPublisher implements RuntimeEventPublisher {
        private final List<RuntimeEvent> events = new ArrayList<>();

        @Override
        public void publish(RuntimeEvent event) {
            events.add(event);
        }

        List<RuntimeEvent> events() {
            return events;
        }
    }
}
