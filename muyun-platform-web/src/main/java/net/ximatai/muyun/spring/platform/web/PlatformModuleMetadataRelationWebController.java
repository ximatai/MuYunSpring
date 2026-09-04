package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMainMetadataCreateCommand;
import net.ximatai.muyun.spring.platform.metadata.ModuleMainMetadataCreationResult;
import net.ximatai.muyun.spring.platform.metadata.ModuleChildMetadataCreateCommand;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataOrchestrationService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataCapabilitySnapshot;
import net.ximatai.muyun.spring.platform.metadata.MetadataModelDeletionService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataCapabilitySnapshotService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldPropertySummary;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldPropertySummaryService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationRecordCount;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationRecordCountService;
import net.ximatai.muyun.spring.platform.metadata.ReferenceTargetFieldCatalog;
import net.ximatai.muyun.spring.platform.metadata.ReferenceTargetFieldCatalogService;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.web.NestedSortableCrudWebSupport;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = ModuleMetadataRelationService.MODULE_ALIAS, title = "平台模块元数据关系")
@RequestMapping("/platform.module/{moduleAlias}/metadata-relations")
public class PlatformModuleMetadataRelationWebController
        extends NestedSortableCrudWebSupport<ModuleMetadataRelation, ModuleMetadataRelationService> {

    private final ModuleMetadataOrchestrationService orchestrationService;
    private final ModuleMetadataCapabilitySnapshotService capabilitySnapshotService;
    private final ModuleMetadataFieldPropertySummaryService propertySummaryService;
    private final ReferenceTargetFieldCatalogService referenceTargetFieldCatalogService;
    private final MetadataModelDeletionService deletionService;
    private final ModuleMetadataRelationRecordCountService recordCountService;

    public PlatformModuleMetadataRelationWebController(ModuleMetadataOrchestrationService orchestrationService,
                                                       ModuleMetadataCapabilitySnapshotService capabilitySnapshotService,
                                                       ModuleMetadataFieldPropertySummaryService propertySummaryService,
                                                       ReferenceTargetFieldCatalogService referenceTargetFieldCatalogService,
                                                       MetadataModelDeletionService deletionService,
                                                       ModuleMetadataRelationRecordCountService recordCountService) {
        this.orchestrationService = Objects.requireNonNull(orchestrationService, "orchestrationService must not be null");
        this.capabilitySnapshotService = Objects.requireNonNull(capabilitySnapshotService, "capabilitySnapshotService must not be null");
        this.propertySummaryService = Objects.requireNonNull(propertySummaryService, "propertySummaryService must not be null");
        this.referenceTargetFieldCatalogService = Objects.requireNonNull(referenceTargetFieldCatalogService,
                "referenceTargetFieldCatalogService must not be null");
        this.deletionService = Objects.requireNonNull(deletionService, "deletionService must not be null");
        this.recordCountService = Objects.requireNonNull(recordCountService, "recordCountService must not be null");
    }

    @PostMapping("/create-main-metadata")
    @CustomActionEndpoint(value = "createMainMetadata", title = "创建模块主实体",
            level = PlatformActionLevel.LIST, dataAuth = false)
    public ModuleMainMetadataCreationResult createMainMetadata(HttpServletRequest request,
                                                               @RequestBody ModuleMainMetadataCreateCommand command) {
        return webScope(() -> orchestrationService.createMainMetadata(moduleAlias(request), command));
    }

    @PostMapping("/{relationId}/create-child-metadata")
    @CustomActionEndpoint(value = "createChildMetadata", title = "创建子元数据",
            level = PlatformActionLevel.RECORD, dataAuth = false)
    public ModuleMainMetadataCreationResult createChildMetadata(HttpServletRequest request,
                                                                @org.springframework.web.bind.annotation.PathVariable String relationId,
                                                                @RequestBody ModuleChildMetadataCreateCommand command) {
        return webScope(() -> orchestrationService.createChildMetadata(moduleAlias(request), relationId, command));
    }

    @DeleteMapping("/{relationId}/fields/{fieldId}")
    @CustomActionEndpoint(value = "deleteMetadataField", title = "删除元数据字段",
            level = PlatformActionLevel.RECORD, dataAuth = false)
    public void deleteField(HttpServletRequest request,
                            @org.springframework.web.bind.annotation.PathVariable String relationId,
                            @org.springframework.web.bind.annotation.PathVariable String fieldId) {
        webScope(() -> { deletionService.deleteField(moduleAlias(request), relationId, fieldId); return null; });
    }

    @DeleteMapping("/{relationId}")
    @CustomActionEndpoint(value = "deleteMetadataNode", title = "删除元数据节点",
            level = PlatformActionLevel.RECORD, dataAuth = false)
    public void deleteMetadata(HttpServletRequest request,
                               @org.springframework.web.bind.annotation.PathVariable String relationId) {
        webScope(() -> { deletionService.deleteMetadata(moduleAlias(request), relationId); return null; });
    }

    @GetMapping("/{relationId}/capabilities")
    @CustomActionEndpoint(value = "viewCapabilitySnapshot", title = "查看元数据能力快照",
            level = PlatformActionLevel.RECORD, dataAuth = false)
    public ModuleMetadataCapabilitySnapshot capabilitySnapshot(HttpServletRequest request,
                                                                @org.springframework.web.bind.annotation.PathVariable String relationId) {
        return webScope(() -> capabilitySnapshotService.snapshot(moduleAlias(request), relationId));
    }

    @GetMapping("/{relationId}/record-count")
    @CustomActionEndpoint(value = "viewMetadataRelationRecordCount", title = "查看元数据关系记录数",
            level = PlatformActionLevel.RECORD, dataAuth = false)
    public ModuleMetadataRelationRecordCount recordCount(HttpServletRequest request,
                                                          @org.springframework.web.bind.annotation.PathVariable String relationId) {
        return webScope(() -> recordCountService.count(moduleAlias(request), relationId));
    }

    @GetMapping("/{relationId}/field-properties")
    @CustomActionEndpoint(value = "viewMetadataFieldProperties", title = "查看字段数据属性",
            level = PlatformActionLevel.RECORD, dataAuth = false)
    public java.util.List<ModuleMetadataFieldPropertySummary> fieldProperties(HttpServletRequest request,
                                                                                @org.springframework.web.bind.annotation.PathVariable String relationId) {
        return webScope(() -> propertySummaryService.list(moduleAlias(request), relationId));
    }

    @GetMapping("/{relationId}/reference-target-field-catalog")
    @CustomActionEndpoint(value = "viewReferenceTargetFieldCatalog", title = "查看引用目标字段目录",
            level = PlatformActionLevel.RECORD, dataAuth = false)
    public ReferenceTargetFieldCatalog referenceTargetFieldCatalog(HttpServletRequest request,
                                                                    @org.springframework.web.bind.annotation.PathVariable String relationId,
                                                                    @org.springframework.web.bind.annotation.RequestParam String targetModuleAlias,
                                                                    @org.springframework.web.bind.annotation.RequestParam(required = false) String targetMetadataId) {
        return webScope(() -> referenceTargetFieldCatalogService.list(moduleAlias(request), relationId,
                targetModuleAlias, targetMetadataId));
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

}
