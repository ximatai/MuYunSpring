package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Restores dynamic runtime registrations after a process restart.
 *
 * <p>The runtime registry is intentionally in-memory. A dynamic module only becomes runnable
 * once its MAIN metadata relation has been published, so startup reactivates those released
 * modules without performing schema migration. Incomplete dynamic modules remain governable but
 * are not exposed as runnable record modules.</p>
 */
@Component
@Order(0)
public class DynamicModuleRuntimeStartupActivator implements ApplicationRunner {
    private static final PageRequest ONE = new PageRequest(0, 1);

    private final PlatformModuleService moduleService;
    private final ModuleMetadataRelationService relationService;
    private final PlatformDynamicRuntimeRefreshService runtimeRefreshService;

    public DynamicModuleRuntimeStartupActivator(PlatformModuleService moduleService,
                                                ModuleMetadataRelationService relationService,
                                                PlatformDynamicRuntimeRefreshService runtimeRefreshService) {
        this.moduleService = moduleService;
        this.relationService = relationService;
        this.runtimeRefreshService = runtimeRefreshService;
    }

    @Override
    public void run(ApplicationArguments args) {
        moduleService.listVisibleModules().stream()
                .filter(module -> module.getModuleKind() == ModuleKind.DYNAMIC)
                .filter(this::hasPublishedMainMetadata)
                .forEach(module -> runtimeRefreshService.activateNow(module.getAlias()));
    }

    private boolean hasPublishedMainMetadata(PlatformModule module) {
        return !relationService.list(Criteria.of()
                        .eq("moduleAlias", module.getAlias())
                        .eq("relationRole", RelationRole.MAIN), ONE)
                .isEmpty();
    }
}
