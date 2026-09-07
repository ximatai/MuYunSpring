package net.ximatai.muyun.spring.platform.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.module.PlatformModule;

import java.util.Comparator;
import java.util.HashSet;

/** Restores persisted dynamic declarations before published page execution plans are installed. */
public final class PlatformDynamicRuntimeBootstrapTask implements PlatformBootstrapTask {
    private final PlatformModuleService modules;
    private final ModuleMetadataRelationService relations;
    private final PlatformDynamicRuntimeRefreshService runtime;

    public PlatformDynamicRuntimeBootstrapTask(PlatformModuleService modules,
                                               ModuleMetadataRelationService relations,
                                               PlatformDynamicRuntimeRefreshService runtime) {
        this.modules = modules;
        this.relations = relations;
        this.runtime = runtime;
    }

    @Override
    public int order() {
        return 300;
    }

    @Override
    public void run() {
        try (var ignored = TenantContext.system("restore configured dynamic modules")) {
            var configured = new HashSet<String>();
            relations.list(Criteria.of().eq("relationRole", RelationRole.MAIN).isNull("tenantId"),
                    new PageRequest(0, Integer.MAX_VALUE)).forEach(relation -> configured.add(relation.getModuleAlias()));
            modules.list(Criteria.of().eq("moduleKind", ModuleKind.DYNAMIC).isNull("tenantId"),
                            new PageRequest(0, Integer.MAX_VALUE)).stream()
                    .filter(module -> configured.contains(module.getAlias()))
                    .sorted(Comparator.comparing(PlatformModule::getAlias))
                    .forEach(module -> runtime.activateNow(module.getAlias()));
        }
    }
}
