package net.ximatai.muyun.spring.platform.runtime;

import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRefreshResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import net.ximatai.muyun.database.core.orm.MigrationOptions;

import java.util.Objects;

@Service
public class PlatformDynamicRuntimeRefreshService {
    private final PlatformDynamicRuntimeRefresher refresher;
    private final DynamicRuntimeActivationService activation;

    public PlatformDynamicRuntimeRefreshService(PlatformDynamicRuntimeRefresher refresher,
                                                DynamicRuntimeActivationService activation) {
        this.refresher = Objects.requireNonNull(refresher, "refresher must not be null");
        this.activation = Objects.requireNonNull(activation, "activation must not be null");
    }

    @Transactional
    public DynamicModuleRefreshResult refresh(String moduleAlias) {
        var result = refresher.prepareSchema(moduleAlias, null);
        if (!result.dryRun()) activation.schedule(moduleAlias);
        return result;
    }

    @Transactional
    public DynamicModuleRefreshResult executeRefresh(String moduleAlias) {
        var result = refresher.prepareSchema(moduleAlias, MigrationOptions.execute());
        if (!result.dryRun()) activation.schedule(moduleAlias);
        return result;
    }

    public DynamicModuleRefreshResult previewRefresh(String moduleAlias) {
        return refresher.previewRefresh(moduleAlias);
    }

}
