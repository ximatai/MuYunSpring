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
import net.ximatai.muyun.spring.platform.metadata.MetadataSystemFieldReconciliationService;
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
import org.springframework.beans.factory.annotation.Autowired;

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
    private final MetadataSystemFieldReconciliationService reconciliationService;
    private ModuleMetadataRelationRecordCountService recordCountService;

    public PlatformModuleMetadataRelationWebController(ModuleMetadataOrchestrationService orchestrationService,
                                                       ModuleMetadataCapabilitySnapshotService capabilitySnapshotService) {
        this(orchestrationService, capabilitySnapshotService, null, null, null, null);
    }

    public PlatformModuleMetadataRelationWebController(ModuleMetadataOrchestrationService orchestrationService,
                                                       ModuleMetadataCapabilitySnapshotService capabilitySnapshotService,
                                                       ModuleMetadataFieldPropertySummaryService propertySummaryService) {
        this(orchestrationService, capabilitySnapshotService, propertySummaryService, null, null, null);
    }

    public PlatformModuleMetadataRelationWebController(ModuleMetadataOrchestrationService orchestrationService,
                                                       ModuleMetadataCapabilitySnapshotService capabilitySnapshotService,
                                                       ModuleMetadataFieldPropertySummaryService propertySummaryService,
                                                       ReferenceTargetFieldCatalogService referenceTargetFieldCatalogService) {
        this(orchestrationService, capabilitySnapshotService, propertySummaryService,
                referenceTargetFieldCatalogService, null, null);
    }

    @Autowired
    public PlatformModuleMetadataRelationWebController(ModuleMetadataOrchestrationService orchestrationService,
                                                       ModuleMetadataCapabilitySnapshotService capabilitySnapshotService,
                                                       ModuleMetadataFieldPropertySummaryService propertySummaryService,
                                                       ReferenceTargetFieldCatalogService referenceTargetFieldCatalogService,
                                                       MetadataModelDeletionService deletionService,
                                                       MetadataSystemFieldReconciliationService reconciliationService) {
        this.orchestrationService = orchestrationService;
        this.capabilitySnapshotService = capabilitySnapshotService;
        this.propertySummaryService = propertySummaryService;
        this.referenceTargetFieldCatalogService = referenceTargetFieldCatalogService;
        this.deletionService = deletionService;
        this.reconciliationService = reconciliationService;
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
        webScope(() -> { deletionService().deleteField(moduleAlias(request), relationId, fieldId); return null; });
    }

    @DeleteMapping("/{relationId}")
    @CustomActionEndpoint(value = "deleteMetadataNode", title = "删除元数据节点",
            level = PlatformActionLevel.RECORD, dataAuth = false)
    public void deleteMetadata(HttpServletRequest request,
                               @org.springframework.web.bind.annotation.PathVariable String relationId) {
        webScope(() -> { deletionService().deleteMetadata(moduleAlias(request), relationId); return null; });
    }

    @PostMapping("/{relationId}/reconcile-system-fields")
    @CustomActionEndpoint(value = "reconcileMetadataSystemFields", title = "回填元数据系统字段",
            level = PlatformActionLevel.RECORD, dataAuth = false)
    public void reconcileSystemFields(HttpServletRequest request,
                                      @org.springframework.web.bind.annotation.PathVariable String relationId) {
        webScope(() -> { reconciliationService().reconcile(moduleAlias(request), relationId); return null; });
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
        return webScope(() -> recordCountService().count(moduleAlias(request), relationId));
    }

    @GetMapping("/{relationId}/field-properties")
    @CustomActionEndpoint(value = "viewMetadataFieldProperties", title = "查看字段数据属性",
            level = PlatformActionLevel.RECORD, dataAuth = false)
    public java.util.List<ModuleMetadataFieldPropertySummary> fieldProperties(HttpServletRequest request,
                                                                                @org.springframework.web.bind.annotation.PathVariable String relationId) {
        return webScope(() -> propertySummary().list(moduleAlias(request), relationId));
    }

    @GetMapping("/{relationId}/reference-target-field-catalog")
    @CustomActionEndpoint(value = "viewReferenceTargetFieldCatalog", title = "查看引用目标字段目录",
            level = PlatformActionLevel.RECORD, dataAuth = false)
    public ReferenceTargetFieldCatalog referenceTargetFieldCatalog(HttpServletRequest request,
                                                                    @org.springframework.web.bind.annotation.PathVariable String relationId,
                                                                    @org.springframework.web.bind.annotation.RequestParam String targetModuleAlias,
                                                                    @org.springframework.web.bind.annotation.RequestParam(required = false) String targetMetadataId) {
        return webScope(() -> referenceTargetFieldCatalog().list(moduleAlias(request), relationId,
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

    private ModuleMetadataFieldPropertySummaryService propertySummary() {
        if (propertySummaryService == null) {
            throw new IllegalStateException("Metadata field property summary is not configured");
        }
        return propertySummaryService;
    }

    private ReferenceTargetFieldCatalogService referenceTargetFieldCatalog() {
        if (referenceTargetFieldCatalogService == null) {
            throw new IllegalStateException("Reference target field catalog is not configured");
        }
        return referenceTargetFieldCatalogService;
    }

    private MetadataModelDeletionService deletionService() {
        if (deletionService == null) throw new IllegalStateException("Metadata model deletion is not configured");
        return deletionService;
    }

    private MetadataSystemFieldReconciliationService reconciliationService() {
        if (reconciliationService == null) {
            throw new IllegalStateException("Metadata system-field reconciliation is not configured");
        }
        return reconciliationService;
    }

    @Autowired
    void setRecordCountService(ModuleMetadataRelationRecordCountService recordCountService) {
        this.recordCountService = recordCountService;
    }

    private ModuleMetadataRelationRecordCountService recordCountService() {
        if (recordCountService == null) throw new IllegalStateException("Metadata relation record count is not configured");
        return recordCountService;
    }
}
