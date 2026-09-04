package net.ximatai.muyun.spring.platform.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Restores published dynamic module registrations after a process restart.
 *
 * <p>The dynamic runtime registry is intentionally in-memory. Only a dynamic module with a
 * persisted MAIN metadata relation has completed metadata publication and is eligible for
 * activation; incomplete modules remain governable without becoming record-runtime modules.</p>
 */
@Service
public class DynamicModuleRuntimeStartupActivationTask implements PlatformBootstrapTask {
    private static final PageRequest ONE = new PageRequest(0, 1);
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamicModuleRuntimeStartupActivationTask.class);

    private final PlatformModuleService moduleService;
    private final ModuleMetadataRelationService relationService;
    private final PlatformDynamicRuntimeRefreshService runtimeRefreshService;

    public DynamicModuleRuntimeStartupActivationTask(PlatformModuleService moduleService,
                                                     ModuleMetadataRelationService relationService,
                                                     PlatformDynamicRuntimeRefreshService runtimeRefreshService) {
        this.moduleService = moduleService;
        this.relationService = relationService;
        this.runtimeRefreshService = runtimeRefreshService;
    }

    @Override
    public String name() {
        return "platform.dynamic-runtime-startup-activation";
    }

    @Override
    public int order() {
        // Initial data may publish metadata in the same startup cycle; restore only after it settles.
        return 110;
    }

    @Override
    public void run() {
        moduleService.listVisibleModules().stream()
                .filter(module -> module.getModuleKind() == ModuleKind.DYNAMIC)
                .filter(this::hasPublishedMainMetadata)
                .forEach(this::activatePublishedModule);
    }

    private void activatePublishedModule(PlatformModule module) {
        try {
            runtimeRefreshService.activateNow(module.getAlias());
        } catch (RuntimeException exception) {
            // A persisted model can become invalid while governance evolves.  Keep the platform
            // available so that the model can be repaired; only its record runtime is withheld.
            LOGGER.warn("Skipped dynamic runtime activation for module {} during startup", module.getAlias(), exception);
        }
    }

    private boolean hasPublishedMainMetadata(PlatformModule module) {
        return !relationService.list(Criteria.of()
                        .eq("moduleAlias", module.getAlias())
                        .eq("relationRole", RelationRole.MAIN), ONE)
                .isEmpty();
    }
}
