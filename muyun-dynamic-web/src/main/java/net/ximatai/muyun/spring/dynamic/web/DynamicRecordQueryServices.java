package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.platform.web.DynamicRelationProjectionReadService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshotService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItemService;
import net.ximatai.muyun.spring.platform.web.ModuleExecutionPlanCatalog;
import net.ximatai.muyun.spring.platform.web.ListQuerySummaryRuntime;
import net.ximatai.muyun.spring.platform.web.PlatformModuleRuntimeContextService;

import java.util.Objects;

public record DynamicRecordQueryServices(
        PlatformPageConfigSnapshotService pageConfigSnapshotService,
        PlatformQueryItemService queryItemService,
        DynamicRelationProjectionReadService relationProjectionReadService,
        ModuleExecutionPlanCatalog executionPlanCatalog,
        ListQuerySummaryRuntime listQuerySummaryRuntime,
        PlatformModuleRuntimeContextService runtimeContextService
) {
    public DynamicRecordQueryServices {
        Objects.requireNonNull(relationProjectionReadService, "relationProjectionReadService");
        Objects.requireNonNull(executionPlanCatalog, "executionPlanCatalog");
    }
}
