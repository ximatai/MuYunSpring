package net.ximatai.muyun.spring.dynamic.refresh;

import net.ximatai.muyun.database.core.orm.MigrationOptions;
import net.ximatai.muyun.database.core.orm.MigrationResult;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DynamicModuleRuntimeRefresher {
    private final DynamicSchemaService schemaService;
    private final DynamicRecordRuntime runtime;

    public DynamicModuleRuntimeRefresher(DynamicSchemaService schemaService, DynamicRecordRuntime runtime) {
        this.schemaService = Objects.requireNonNull(schemaService, "schemaService must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
    }

    public DynamicModuleRefreshResult refresh(ModuleDefinition module) {
        return refresh(module, null);
    }

    public DynamicModuleRefreshResult previewRefresh(ModuleDefinition module) {
        return refresh(module, MigrationOptions.dryRun());
    }

    /** Activates an already schema-ensured module without DDL, transaction deferral, or refresh events. */
    public DynamicModuleRefreshResult activateNow(ModuleDefinition module) {
        runtime.refresh(module);
        return new DynamicModuleRefreshResult(module, Map.of(), false);
    }

    /** Drops an installed runtime projection after its dynamic module loses its MAIN entity. */
    public void deactivateNow(String moduleAlias) {
        runtime.registry().unregister(moduleAlias);
    }

    public DynamicModuleRefreshResult refresh(ModuleDefinition module, MigrationOptions options) {
        ModuleDefinition previousModule = runtime.registry().findModule(module.moduleAlias()).orElse(null);
        Map<String, MigrationResult> migrations = schemaService.ensureModule(module, previousModule, options);
        boolean dryRun = migrations != null && !migrations.isEmpty()
                ? migrations.values().stream().allMatch(MigrationResult::isDryRun)
                : options != null && options.isDryRun();
        if (!dryRun) {
            runtime.refresh(module);
            publishModuleEvent(module, migrations);
        }
        return new DynamicModuleRefreshResult(module, migrations, dryRun);
    }

    private void publishModuleEvent(ModuleDefinition module, Map<String, MigrationResult> migrations) {
        DynamicModuleRefreshResult result = new DynamicModuleRefreshResult(module, migrations, false);
        String systemReason = TenantContext.systemReason().orElse("dynamic module refresh");
        runtime.eventPublisher().publishAfterCommit(RuntimeEvent.of(
                RuntimeEventType.MODULE_REFRESHED,
                module.moduleAlias(),
                null,
                null,
                null,
                TenantContext.currentTenantId().orElse(null),
                true,
                systemReason,
                RuntimeMutationSource.SYSTEM,
                Map.of(
                        "changed", result.changed(),
                        "entities", result.migrations().entrySet().stream()
                                .map(entry -> Map.of(
                                        "entityAlias", entry.getKey(),
                                        "changed", entry.getValue().isChanged(),
                                        "dryRun", entry.getValue().isDryRun(),
                                        "nonAdditiveChanges", entry.getValue().hasNonAdditiveChanges(),
                                        "statements", entry.getValue().getStatements(),
                                        "changes", migrationChanges(entry.getValue())
                                ))
                                .toList(),
                        "nonAdditiveChanges", result.hasNonAdditiveChanges()
                )
        ));
    }

    private List<Map<String, Object>> migrationChanges(MigrationResult result) {
        return result.getChanges().stream()
                .map(change -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("type", change.getType().name());
                    payload.put("target", change.getTarget());
                    payload.put("sql", change.getSql());
                    payload.put("nonAdditive", change.isNonAdditive());
                    return payload;
                })
                .toList();
    }
}
