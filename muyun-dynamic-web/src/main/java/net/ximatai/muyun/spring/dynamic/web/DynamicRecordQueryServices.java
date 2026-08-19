package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.platform.web.DynamicRelationProjectionReadService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshotService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItemService;
import net.ximatai.muyun.spring.platform.web.ModuleExecutionPlanCatalog;

import java.util.Objects;

public record DynamicRecordQueryServices(
        PlatformPageConfigSnapshotService pageConfigSnapshotService,
        PlatformQueryItemService queryItemService,
        ModuleMetadataFieldService moduleMetadataFieldService,
        FieldUiControlService fieldUiControlService,
        FieldUiControlBindingService fieldUiControlBindingService,
        DynamicRelationProjectionReadService relationProjectionReadService,
        ModuleExecutionPlanCatalog executionPlanCatalog
) {
    public DynamicRecordQueryServices(PlatformPageConfigSnapshotService pageConfigSnapshotService,
                                      PlatformQueryItemService queryItemService,
                                      ModuleMetadataFieldService moduleMetadataFieldService,
                                      FieldUiControlService fieldUiControlService,
                                      FieldUiControlBindingService fieldUiControlBindingService,
                                      DynamicRelationProjectionReadService relationProjectionReadService) {
        this(pageConfigSnapshotService, queryItemService, moduleMetadataFieldService, fieldUiControlService,
                fieldUiControlBindingService, relationProjectionReadService, null);
    }

    public DynamicRecordQueryServices(PlatformPageConfigSnapshotService pageConfigSnapshotService,
                                      PlatformQueryItemService queryItemService,
                                      ModuleMetadataFieldService moduleMetadataFieldService,
                                      DynamicRelationProjectionReadService relationProjectionReadService) {
        this(pageConfigSnapshotService, queryItemService, moduleMetadataFieldService, null, null,
                relationProjectionReadService, null);
    }
    public DynamicRecordQueryServices {
        Objects.requireNonNull(relationProjectionReadService, "relationProjectionReadService");
    }
}
