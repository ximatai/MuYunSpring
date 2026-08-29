package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMainMetadataCreateCommand;
import net.ximatai.muyun.spring.platform.metadata.ModuleMainMetadataCreationResult;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataOrchestrationService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataCapabilitySnapshot;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataCapabilitySnapshotService;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewCommand;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreview;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewService;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyCommand;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyService;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPublishResult;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.web.NestedSortableCrudWebSupport;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = ModuleMetadataRelationService.MODULE_ALIAS, title = "平台模块元数据关系")
@RequestMapping("/platform.module/{moduleAlias}/metadata-relations")
public class PlatformModuleMetadataRelationWebController
        extends NestedSortableCrudWebSupport<ModuleMetadataRelation, ModuleMetadataRelationService> {

    private final ModuleMetadataOrchestrationService orchestrationService;
    private final ModuleMetadataCapabilitySnapshotService capabilitySnapshotService;
    private final MetadataRelationChangeSetPreviewService changeSetPreviewService;
    private final MetadataRelationChangeSetApplyService changeSetApplyService;

    @Autowired
    public PlatformModuleMetadataRelationWebController(ModuleMetadataOrchestrationService orchestrationService,
                                                       ModuleMetadataCapabilitySnapshotService capabilitySnapshotService,
                                                       MetadataRelationChangeSetPreviewService changeSetPreviewService,
                                                       MetadataRelationChangeSetApplyService changeSetApplyService) {
        this.orchestrationService = orchestrationService;
        this.capabilitySnapshotService = capabilitySnapshotService;
        this.changeSetPreviewService = changeSetPreviewService;
        this.changeSetApplyService = changeSetApplyService;
    }

    @PostMapping("/create-main-metadata")
    @CustomActionEndpoint(value = "createMainMetadata", title = "创建模块主实体",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public ModuleMainMetadataCreationResult createMainMetadata(HttpServletRequest request,
                                                               @RequestBody ModuleMainMetadataCreateCommand command) {
        return webScope(() -> orchestrationService.createMainMetadata(moduleAlias(request), command));
    }

    @GetMapping("/{relationId}/capabilities")
    @CustomActionEndpoint(value = "viewCapabilitySnapshot", title = "查看元数据能力快照",
            level = PlatformActionLevel.RECORD, dataAuth = false)
    public ModuleMetadataCapabilitySnapshot capabilitySnapshot(HttpServletRequest request,
                                                                @org.springframework.web.bind.annotation.PathVariable String relationId) {
        return webScope(() -> capabilitySnapshotService.snapshot(moduleAlias(request), relationId));
    }

    @PostMapping("/{relationId}/change-set-preview")
    @CustomActionEndpoint(value = "previewMetadataChangeSet", title = "预检元数据变更集",
            level = PlatformActionLevel.RECORD, dataAuth = false)
    public MetadataRelationChangeSetPreview changeSetPreview(HttpServletRequest request,
                                                              @org.springframework.web.bind.annotation.PathVariable String relationId,
                                                              @RequestBody MetadataRelationChangeSetPreviewCommand command) {
        return webScope(() -> changeSetPreview().preview(moduleAlias(request), relationId, command));
    }

    @PostMapping("/{relationId}/change-set-apply")
    @CustomActionEndpoint(value = "applyMetadataChangeSet", title = "发布元数据变更集",
            level = PlatformActionLevel.RECORD, dataAuth = false)
    public MetadataRelationChangeSetPublishResult applyChangeSet(HttpServletRequest request,
                                                                  @org.springframework.web.bind.annotation.PathVariable String relationId,
                                                                  @RequestBody MetadataRelationChangeSetApplyCommand command) {
        return webScope(() -> changeSetApply().apply(moduleAlias(request), relationId, command));
    }

    @Override
    protected void appendScope(Criteria criteria, HttpServletRequest request) {
        criteria.eq("moduleAlias", moduleAlias(request));
    }

    @Override
    protected void bindScope(ModuleMetadataRelation record, HttpServletRequest request) {
        record.setModuleAlias(moduleAlias(request));
    }

    @Override
    protected boolean inScope(ModuleMetadataRelation record, HttpServletRequest request) {
        return moduleAlias(request).equals(record.getModuleAlias());
    }

    @Override
    protected String scopedRecordNotFoundMessage(HttpServletRequest request, String id) {
        return "metadata relation does not belong to module: " + moduleAlias(request) + "." + id;
    }

    private String moduleAlias(HttpServletRequest request) {
        return PlatformNameRules.requireModuleAlias(pathVariable(request, "moduleAlias"));
    }

    private MetadataRelationChangeSetPreviewService changeSetPreview() {
        if (changeSetPreviewService == null) {
            throw new IllegalStateException("Metadata change-set preview is not configured");
        }
        return changeSetPreviewService;
    }

    private MetadataRelationChangeSetApplyService changeSetApply() {
        if (changeSetApplyService == null) {
            throw new IllegalStateException("Metadata change-set apply is not configured");
        }
        return changeSetApplyService;
    }
}
