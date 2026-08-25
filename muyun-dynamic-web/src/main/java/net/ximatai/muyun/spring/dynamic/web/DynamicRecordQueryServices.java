package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.platform.web.DynamicRelationProjectionReadService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshotService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItemService;
import net.ximatai.muyun.spring.platform.web.ModuleExecutionPlanCatalog;
import net.ximatai.muyun.spring.platform.web.ListQuerySummaryRuntime;
import net.ximatai.muyun.spring.platform.web.PlatformModuleRuntimeContextService;

import java.util.Objects;

public record DynamicRecordQueryServices(
        PlatformPageConfigSnapshotService pageConfigSnapshotService,
        PlatformQueryItemService queryItemService,
        ModuleMetadataFieldService moduleMetadataFieldService,
        FieldUiControlService fieldUiControlService,
        FieldUiControlBindingService fieldUiControlBindingService,
        DynamicRelationProjectionReadService relationProjectionReadService,
        ModuleExecutionPlanCatalog executionPlanCatalog,
        ListQuerySummaryRuntime listQuerySummaryRuntime,
        PlatformModuleRuntimeContextService runtimeContextService
) {
    public DynamicRecordQueryServices(PlatformPageConfigSnapshotService pageConfigSnapshotService,
                                      PlatformQueryItemService queryItemService,
                                      ModuleMetadataFieldService moduleMetadataFieldService,
                                      FieldUiControlService fieldUiControlService,
                                      FieldUiControlBindingService fieldUiControlBindingService,
                                      DynamicRelationProjectionReadService relationProjectionReadService) {
        this(pageConfigSnapshotService, queryItemService, moduleMetadataFieldService, fieldUiControlService,
                fieldUiControlBindingService, relationProjectionReadService, null, null, null);
    }

    /** Source-compatible test and embedding constructor without summary runtime wiring. */
    public DynamicRecordQueryServices(PlatformPageConfigSnapshotService pageConfigSnapshotService,
                                      PlatformQueryItemService queryItemService,
                                      ModuleMetadataFieldService moduleMetadataFieldService,
                                      FieldUiControlService fieldUiControlService,
                                      FieldUiControlBindingService fieldUiControlBindingService,
                                      DynamicRelationProjectionReadService relationProjectionReadService,
                                      ModuleExecutionPlanCatalog executionPlanCatalog) {
        this(pageConfigSnapshotService, queryItemService, moduleMetadataFieldService, fieldUiControlService,
                fieldUiControlBindingService, relationProjectionReadService, executionPlanCatalog, null, null);
    }

    public DynamicRecordQueryServices(PlatformPageConfigSnapshotService pageConfigSnapshotService,
                                      PlatformQueryItemService queryItemService,
                                      ModuleMetadataFieldService moduleMetadataFieldService,
                                      DynamicRelationProjectionReadService relationProjectionReadService) {
        this(pageConfigSnapshotService, queryItemService, moduleMetadataFieldService, null, null,
                relationProjectionReadService, null, null, null);
    }
    public DynamicRecordQueryServices {
        Objects.requireNonNull(relationProjectionReadService, "relationProjectionReadService");
    }
}
