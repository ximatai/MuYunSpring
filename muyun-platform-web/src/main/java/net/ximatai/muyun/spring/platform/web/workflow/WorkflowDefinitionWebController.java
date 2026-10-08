package net.ximatai.muyun.spring.platform.web.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.PlatformStaticWebScope;
import net.ximatai.muyun.spring.web.NestedSortableCrudWebSupport;
import net.ximatai.muyun.spring.web.RecordActionWebRequest;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowDefinition;
import net.ximatai.muyun.spring.platform.workflow.WorkflowDefinitionService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowDefinitionStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowPublishFacade;
import net.ximatai.muyun.spring.platform.workflow.WorkflowVersion;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@PlatformStaticWebScope(PlatformStaticWebScope.Scope.CUSTOM)
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = WorkflowDefinitionService.MODULE_ALIAS,
        title = "平台工作流定义")
@RequestMapping("/platform.module/{moduleAlias}/workflow-definitions")
public class WorkflowDefinitionWebController
        extends NestedSortableCrudWebSupport<WorkflowDefinition, WorkflowDefinitionService> {

    private final PlatformModuleService moduleService;
    private final WorkflowPublishFacade publishFacade;
    private final net.ximatai.muyun.spring.platform.workflow.WorkflowDesignService designs;

    public WorkflowDefinitionWebController(PlatformModuleService moduleService,
                                           WorkflowPublishFacade publishFacade, net.ximatai.muyun.spring.platform.workflow.WorkflowDesignService designs) {
        this.moduleService = Objects.requireNonNull(moduleService, "moduleService must not be null");
        this.publishFacade = Objects.requireNonNull(publishFacade, "publishFacade must not be null");
        this.designs = Objects.requireNonNull(designs, "designs must not be null");
    }

    private net.ximatai.muyun.spring.platform.workflow.WorkflowConfigurationCatalogService configurationCatalog;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowDefinitionWebController(PlatformModuleService modules, WorkflowPublishFacade publish,
            net.ximatai.muyun.spring.platform.workflow.WorkflowDesignService designs,
            net.ximatai.muyun.spring.platform.workflow.WorkflowConfigurationCatalogService catalog) {
        this(modules, publish, designs);
        this.configurationCatalog = Objects.requireNonNull(catalog);
    }

    @org.springframework.web.bind.annotation.GetMapping("/{definitionId}/configuration-catalog")
    @CustomActionEndpoint(value = "viewWorkflowDesign", title = "查看流程设计", level = PlatformActionLevel.RECORD,
            dataAuth = true, recordIdPathVariable = "definitionId")
    public net.ximatai.muyun.spring.platform.workflow.WorkflowConfigurationCatalogService.Catalog catalog(
            HttpServletRequest request, @PathVariable String definitionId) {
        return webScope(() -> { var definition = requireScopedRecord(request, definitionId);
            return configurationCatalog.forModule(definition.getModuleAlias()); });
    }

    @Override
    protected void appendScope(Criteria criteria, HttpServletRequest request) {
        criteria.eq("moduleAlias", moduleAlias(request));
    }

    @Override
    protected void bindScope(WorkflowDefinition record, HttpServletRequest request) {
        PlatformModule module = requireModule(request);
        record.setApplicationAlias(module.getApplicationAlias());
        record.setModuleAlias(module.getAlias());
    }

    @Override
    protected boolean inScope(WorkflowDefinition record, HttpServletRequest request) {
        return moduleAlias(request).equals(record.getModuleAlias());
    }

    @Override
    protected String scopedRecordNotFoundMessage(HttpServletRequest request, String id) {
        return "workflow definition does not belong to module: " + moduleAlias(request) + "." + id;
    }

    @Override
    @PostMapping("/insert")
    @ActionEndpoint(PlatformAction.CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowDefinition insert(HttpServletRequest servletRequest,
                                     @RequestBody WorkflowDefinition record) {
        normalizeDraft(record);
        return super.insert(servletRequest, record);
    }

    @Override
    @PostMapping("/update/{id}")
    @ActionEndpoint(PlatformAction.UPDATE)
    public WorkflowDefinition update(HttpServletRequest servletRequest,
                                     @PathVariable String id,
                                     @RequestBody WorkflowDefinition record) {
        requireDraft(requireScopedRecord(servletRequest, id), "workflow definition can only edit draft definitions");
        normalizeDraft(record);
        return super.update(servletRequest, id, record);
    }

    @Override
    @PostMapping("/delete/{id}")
    @ActionEndpoint(PlatformAction.DELETE)
    public int delete(HttpServletRequest servletRequest, @PathVariable String id,
                      @RequestBody RecordActionWebRequest request) {
        requireDraft(requireScopedRecord(servletRequest, id), "workflow definition can only delete draft definitions");
        return super.delete(servletRequest, id, request);
    }

    @PostMapping("/{definitionId}/versions/{versionId}/publish")
    @CustomActionEndpoint(value = "publishWorkflowDefinition", title = "发布工作流定义",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "definitionId")
    public WorkflowVersion publish(HttpServletRequest request,
                                   @PathVariable String definitionId,
                                   @PathVariable String versionId,
                                   @RequestBody WorkflowPublishWebRequest publishRequest) {
        return webScope(() -> {
            requireScopedRecord(request, definitionId);
            return publishFacade.publish(definitionId, versionId, publishRequest.definitionVersion(),
                    publishRequest.version(), currentOperatorIdOrNull());
        });
    }

    @PostMapping("/{definitionId}/disable")
    @CustomActionEndpoint(value = "disableWorkflowDefinition", title = "停用工作流定义",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "definitionId")
    public WorkflowDefinition disableDefinition(HttpServletRequest request, @PathVariable String definitionId,
                                                @RequestBody RecordActionWebRequest actionRequest) {
        return webScope(() -> {
            requireScopedRecord(request, definitionId);
            return publishFacade.disable(definitionId, actionRequest.version());
        });
    }

    @PostMapping("/{definitionId}/selection")
    @CustomActionEndpoint(value = "configureWorkflowSelection", title = "调整流程匹配规则",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "definitionId")
    public WorkflowDefinition configureSelection(HttpServletRequest request, @PathVariable String definitionId,
            @RequestBody net.ximatai.muyun.spring.platform.workflow.WorkflowDesignService.SelectionSettings settings) {
        return webScope(() -> { requireScopedRecord(request, definitionId); return designs.configureSelection(definitionId, settings); });
    }

    @PostMapping("/{definitionId}/archive")
    @CustomActionEndpoint(value = "archiveWorkflowDefinition", title = "归档工作流定义",
            level = PlatformActionLevel.RECORD, dataAuth = true, recordIdPathVariable = "definitionId")
    public WorkflowDefinition archive(HttpServletRequest request, @PathVariable String definitionId,
                                      @RequestBody RecordActionWebRequest actionRequest) {
        return webScope(() -> {
            requireScopedRecord(request, definitionId);
            return publishFacade.archive(definitionId, actionRequest.version());
        });
    }

    @org.springframework.web.bind.annotation.GetMapping("/{definitionId}/versions/{versionId}/design")
    @CustomActionEndpoint(value = "viewWorkflowDesign", title = "查看流程设计", level = PlatformActionLevel.RECORD,
            dataAuth = true, recordIdPathVariable = "definitionId")
    public net.ximatai.muyun.spring.platform.workflow.WorkflowDesignDocument design(HttpServletRequest request,
            @PathVariable String definitionId, @PathVariable String versionId) {
        return webScope(() -> { requireScopedRecord(request, definitionId); return designs.read(definitionId, versionId); });
    }

    @PostMapping("/{definitionId}/versions/{versionId}/design")
    @CustomActionEndpoint(value = "editWorkflowDesign", title = "保存流程设计", level = PlatformActionLevel.RECORD,
            dataAuth = true, recordIdPathVariable = "definitionId")
    public WorkflowVersion saveDesign(HttpServletRequest request, @PathVariable String definitionId,
            @PathVariable String versionId, @RequestBody WorkflowDesignSaveWebRequest payload) {
        return webScope(() -> { requireScopedRecord(request, definitionId);
            return designs.save(definitionId, versionId, payload.version(), payload.design()); });
    }

    @PostMapping("/{definitionId}/versions/{versionId}/validate")
    @CustomActionEndpoint(value = "validateWorkflowDesign", title = "校验流程设计", level = PlatformActionLevel.RECORD,
            dataAuth = true, recordIdPathVariable = "definitionId")
    public net.ximatai.muyun.spring.platform.workflow.WorkflowDesignDocument validateDesign(HttpServletRequest request,
            @PathVariable String definitionId, @PathVariable String versionId) {
        return webScope(() -> { requireScopedRecord(request, definitionId); return designs.validate(definitionId, versionId); });
    }

    @PostMapping("/{definitionId}/upgrade")
    @CustomActionEndpoint(value = "upgradeWorkflowDefinition", title = "创建流程新版本", level = PlatformActionLevel.RECORD,
            dataAuth = true, recordIdPathVariable = "definitionId")
    public WorkflowVersion upgrade(HttpServletRequest request, @PathVariable String definitionId) {
        return webScope(() -> { requireScopedRecord(request, definitionId); return designs.upgrade(definitionId); });
    }

    private String moduleAlias(HttpServletRequest request) {
        return PlatformNameRules.requireModuleAlias(pathVariable(request, "moduleAlias"));
    }

    private PlatformModule requireModule(HttpServletRequest request) {
        String validModuleAlias = moduleAlias(request);
        PlatformModule module = moduleService.resolveVisibleModule(validModuleAlias);
        if (module == null) {
            throw new IllegalArgumentException("platform module not found: " + validModuleAlias);
        }
        return module;
    }

    private void normalizeDraft(WorkflowDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("workflow definition must not be null");
        }
        definition.setDefinitionStatus(WorkflowDefinitionStatus.DRAFT);
        definition.setCurrentVersionNo(null);
    }

    private void requireDraft(WorkflowDefinition definition, String message) {
        if (definition.getDefinitionStatus() != WorkflowDefinitionStatus.DRAFT) {
            throw new IllegalArgumentException(message + ": " + definition.getId());
        }
    }

    private String currentOperatorIdOrNull() {
        return CurrentUserContext.currentUser()
                .map(user -> user.userId())
                .filter(userId -> !userId.isBlank())
                .orElse(null);
    }

    public record WorkflowPublishWebRequest(Integer definitionVersion, Integer version) {
        public WorkflowPublishWebRequest {
            if (definitionVersion == null || version == null) {
                throw new IllegalArgumentException("definitionVersion and version are required for workflow publish");
            }
        }
    }
}

record WorkflowDesignSaveWebRequest(Integer version, net.ximatai.muyun.spring.platform.workflow.WorkflowDesignDocument design) {}
