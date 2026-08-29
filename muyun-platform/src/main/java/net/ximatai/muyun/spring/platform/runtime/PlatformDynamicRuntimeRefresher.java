package net.ximatai.muyun.spring.platform.runtime;

import net.ximatai.muyun.database.core.orm.MigrationOptions;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRefreshResult;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRuntimeRefresher;
import org.springframework.stereotype.Service;

@Service
public class PlatformDynamicRuntimeRefresher {
    private final PlatformModuleDefinitionCompiler compiler;
    private final DynamicModuleRuntimeRefresher refresher;

    public PlatformDynamicRuntimeRefresher(PlatformModuleDefinitionCompiler compiler, DynamicModuleRuntimeRefresher refresher) {
        this.compiler = compiler;
        this.refresher = refresher;
    }

    public DynamicModuleRefreshResult refresh(String moduleAlias) {
        ModuleDefinition definition = compiler.compile(moduleAlias);
        return refresher.refresh(definition);
    }

    public DynamicModuleRefreshResult executeRefresh(String moduleAlias) {
        return refresh(moduleAlias, MigrationOptions.execute());
    }

    public DynamicModuleRefreshResult previewRefresh(String moduleAlias) {
        return refresh(moduleAlias, MigrationOptions.dryRun());
    }

    /** Activates a module after its schema was ensured by an enclosing configuration release. */
    public DynamicModuleRefreshResult activateNow(String moduleAlias) {
        return refresher.activateNow(compiler.compile(moduleAlias));
    }

    public DynamicModuleRefreshResult refresh(String moduleAlias, MigrationOptions options) {
        ModuleDefinition definition = compiler.compile(moduleAlias);
        return refresher.refresh(definition, options);
    }
}
