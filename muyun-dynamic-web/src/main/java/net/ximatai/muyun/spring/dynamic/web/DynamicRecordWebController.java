package net.ximatai.muyun.spring.dynamic.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.web.ActionWeb;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.web.EnableWeb;
import net.ximatai.muyun.spring.web.ReferenceWeb;
import net.ximatai.muyun.spring.web.TreeSortWebRequest;
import net.ximatai.muyun.spring.web.TreeWeb;
import net.ximatai.muyun.spring.web.WebOutputSupport;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebQueryCondition;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.platform.web.DynamicRelationProjectionReadService;
import net.ximatai.muyun.spring.platform.web.PlatformDynamicModuleScopeService;
import net.ximatai.muyun.spring.platform.web.ProjectionQueryDescriptor;
import net.ximatai.muyun.spring.platform.web.ProjectionQueryFallbackReason;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.common.exception.ErrorTarget;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.web.PlatformWebPathRules;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.platform.attachment.RecordAttachment;
import net.ximatai.muyun.spring.platform.attachment.RecordAttachmentAccess;
import net.ximatai.muyun.spring.platform.attachment.RecordAttachmentAccessService;
import net.ximatai.muyun.spring.platform.attachment.RecordAttachmentCommand;
import net.ximatai.muyun.spring.platform.attachment.RecordAttachmentService;
import net.ximatai.muyun.spring.platform.code.CodeBusinessPreviewItem;
import net.ximatai.muyun.spring.platform.code.CodeBusinessPreviewService;
import net.ximatai.muyun.spring.platform.duplicate.RecordDuplicateCheckResult;
import net.ximatai.muyun.spring.platform.duplicate.RecordDuplicateCheckService;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationCommitResult;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationDraft;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationResult;
import net.ximatai.muyun.spring.platform.generation.ReferenceRecordGenerationFacade;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.metadata.ResolvedModuleMetadataField;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.openapi.DynamicOpenApiGenerator;
import net.ximatai.muyun.spring.common.openapi.OpenApi31Projector;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionException;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionAvailability;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicAssociationViewDiagnosis;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationRelationOverview;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicQuerySchemas;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicQueryCondition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceMatchMode;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceResolveMode;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceResolveRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceResolveResponse;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshot;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshotService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItemService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplate;
import net.ximatai.muyun.spring.platform.ui.PlatformRecordNavigationContext;
import net.ximatai.muyun.spring.platform.ui.PlatformRecordNavigationMove;
import net.ximatai.muyun.spring.platform.ui.PlatformRecordNavigationService;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigField;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSet;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetType;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

@RestController
@RequestMapping("/{moduleAlias:[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+}")
public class DynamicRecordWebController implements
        CrudWeb<DynamicRecord, DynamicEntityOperations>,
        EnableWeb<DynamicRecord, DynamicEntityOperations>,
        TreeWeb<DynamicRecord, DynamicEntityOperations>,
        ActionWeb<DynamicEntityOperations,
                DynamicWebActionRequest,
                DynamicActionDescriptor,
                DynamicWebActionExecutionResponse>,
        ReferenceWeb<DynamicEntityOperations,
                DynamicWebReferenceRequest,
                DynamicReferenceResolveResponse> {
    private final DynamicRecordService recordService;
    private final CodeBusinessPreviewService codeBusinessPreviewService;
    private final ReferenceRecordGenerationFacade referenceRecordGenerationFacade;
    private final PlatformPageConfigSnapshotService pageConfigSnapshotService;
    private final PlatformQueryItemService queryItemService;
    private final ModuleMetadataFieldService moduleMetadataFieldService;
    private final FieldUiControlService fieldUiControlService;
    private final FieldUiControlBindingService fieldUiControlBindingService;
    private final RecordAttachmentService recordAttachmentService;
    private final RecordAttachmentAccessService recordAttachmentAccessService;
    private final RecordDuplicateCheckService duplicateCheckService;
    private final PlatformRecordNavigationService navigationService;
    private final DynamicRelationProjectionReadService dynamicRelationProjectionReadService;
    private final PlatformDynamicModuleScopeService dynamicModuleScopeService;
    private final DynamicOpenApiGenerator openApiGenerator = new DynamicOpenApiGenerator();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SUMMARY_MAX_RECORDS = 10_000;

    @Autowired
    public DynamicRecordWebController(DynamicRecordService recordService,
                                      ActiveTenantVerifier activeTenantVerifier,
                                      DynamicRecordQueryServices queryServices,
                                      DynamicRecordAttachmentServices attachmentServices,
                                      DynamicRecordActionServices actionServices) {
        this.recordService = recordService;
        this.codeBusinessPreviewService = actionServices.codeBusinessPreviewService();
        this.referenceRecordGenerationFacade = actionServices.referenceRecordGenerationFacade();
        this.pageConfigSnapshotService = queryServices.pageConfigSnapshotService();
        this.queryItemService = queryServices.queryItemService();
        this.moduleMetadataFieldService = queryServices.moduleMetadataFieldService();
        this.fieldUiControlService = queryServices.fieldUiControlService();
        this.fieldUiControlBindingService = queryServices.fieldUiControlBindingService();
        this.recordAttachmentService = attachmentServices.attachmentService();
        this.recordAttachmentAccessService = attachmentServices.attachmentAccessService();
        this.duplicateCheckService = actionServices.duplicateCheckService();
        this.navigationService = actionServices.navigationService();
        this.dynamicRelationProjectionReadService = queryServices.relationProjectionReadService();
        this.dynamicModuleScopeService = new PlatformDynamicModuleScopeService(activeTenantVerifier);
    }

    @Override
    public DynamicEntityOperations service() {
        return recordService.mainEntity(DynamicWebRequest.moduleAlias());
    }

    @Override
    @GetMapping("/query/schema")
    @ActionEndpoint(PlatformAction.QUERY)
    public QuerySchema querySchema(@RequestParam(required = false) String uiConfigId) {
        return webScope(() -> {
            String queryTemplateId = DynamicWebRequest.queryParameter("queryTemplateId");
            if (hasText(queryTemplateId)) {
                validateQueryTemplateBelongsToModule(DynamicWebRequest.moduleAlias(), queryTemplateId);
            }
            return DynamicQuerySchemas.from(DynamicWebRequest.moduleAlias(),
                    service().describe(), quickSearchFieldsForSchema(uiConfigId),
                    querySchemaExternalCriteriaKeys(DynamicWebRequest.moduleAlias(), uiConfigId, queryTemplateId));
        });
    }

    @Override
    public <T> T webScope(Supplier<T> action) {
        return tenantScope(DynamicWebRequest.moduleAlias(), action);
    }

    @Override
    public Criteria queryCriteria(WebQueryRequest request) {
        Criteria templateCriteria = Criteria.of();
        if (request != null && hasText(request.queryTemplateId())) {
            requireLowCodeQueryServices();
            validateQueryTemplateBelongsToModule(DynamicWebRequest.moduleAlias(), request.queryTemplateId());
            templateCriteria = queryItemService.compile(request.queryTemplateId(), request.externalQueryValues());
        }
        Criteria manualCriteria = request == null || request.conditions().isEmpty()
                ? Criteria.of()
                : service().queryCriteria(DynamicWebQueryMapper.queryConditions(request.conditions()));
        Criteria treeCriteria = request == null || request.criteria() == null
                ? Criteria.of()
                : DynamicWebQueryMapper.queryCriteria(request.criteria(), service()::queryCriteria);
        Criteria queryFormCriteria = DynamicWebQueryFormSupport.queryFormCriteria(DynamicWebRequest.moduleAlias(),
                request, pageConfigSnapshotService, moduleMetadataFieldService, fieldUiControlService,
                fieldUiControlBindingService, service()::queryCriteria);
        Criteria quickCriteria = quickSearchCriteria(DynamicWebRequest.moduleAlias(), request);
        Criteria workspaceCriteria = scopedListWorkspaceCriteria(DynamicWebRequest.moduleAlias(), request);
        return andCriteria(templateCriteria, queryFormCriteria, manualCriteria, treeCriteria, quickCriteria, workspaceCriteria);
    }

    private List<String> querySchemaExternalCriteriaKeys(String moduleAlias, String uiConfigId,
                                                          String queryTemplateId) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        scopedListWorkspaceCriteriaKey(moduleAlias, uiConfigId).ifPresent(keys::add);
        if (queryItemService != null && hasText(queryTemplateId)) {
            keys.addAll(queryItemService.externalValueKeys(queryTemplateId));
        }
        return List.copyOf(keys);
    }

    private Criteria scopedListWorkspaceCriteria(String moduleAlias, WebQueryRequest request) {
        if (request == null || !hasText(request.uiConfigId()) || request.externalQueryValues() == null) {
            return Criteria.of();
        }
        PlatformPageConfigSnapshot snapshot = pageConfigSnapshotService.snapshot(moduleAlias);
        PlatformUiConfig uiConfig = publishedUiConfig(snapshot, request.uiConfigId());
        String criteriaKey = scopedListWorkspaceCriteriaKey(uiConfig);
        if (criteriaKey == null || !request.externalQueryValues().containsKey(criteriaKey)) {
            return Criteria.of();
        }
        Object scopeValue = request.externalQueryValues().get(criteriaKey);
        return scopeValue == null ? Criteria.of() : Criteria.of().eq(uiConfig.getScopeField(), scopeValue);
    }

    private java.util.Optional<String> scopedListWorkspaceCriteriaKey(String moduleAlias, String uiConfigId) {
        if (!hasText(uiConfigId) || pageConfigSnapshotService == null) {
            return java.util.Optional.empty();
        }
        PlatformUiConfig uiConfig = publishedUiConfig(pageConfigSnapshotService.snapshot(moduleAlias), uiConfigId);
        return java.util.Optional.ofNullable(scopedListWorkspaceCriteriaKey(uiConfig));
    }

    private String scopedListWorkspaceCriteriaKey(PlatformUiConfig uiConfig) {
        if (uiConfig.getScopeModuleAlias() == null || uiConfig.getScopeModuleAlias().isBlank()
                || uiConfig.getScopeField() == null || uiConfig.getScopeField().isBlank()) {
            return null;
        }
        return hasText(uiConfig.getScopeQueryCriteriaKey())
                ? uiConfig.getScopeQueryCriteriaKey()
                : uiConfig.getScopeField();
    }

    private Criteria andCriteria(Criteria... criteriaList) {
        Criteria single = null;
        int size = 0;
        for (Criteria item : criteriaList) {
            if (item != null && !item.isEmpty()) {
                single = item;
                size++;
            }
        }
        if (size == 0) {
            return Criteria.of();
        }
        if (size == 1) {
            return single;
        }
        Criteria criteria = Criteria.of();
        for (Criteria item : criteriaList) {
            if (item != null && !item.isEmpty()) {
                criteria.andGroup(item.getRoot());
            }
        }
        return criteria;
    }

    @Override
    public Sort[] querySorts(WebQueryRequest request) {
        if (request == null || request.sorts().isEmpty()) {
            return new Sort[0];
        }
        DynamicWebQueryFieldSupport.validatePhysicalSorts(service(), request.sorts());
        return DynamicWebQueryMapper.sorts(request.sorts());
    }

    @Override
    public PageResult<DynamicRecord> queryRecords(WebQueryRequest request) {
        if (request == null || !hasText(request.uiConfigId())) {
            return CrudWeb.super.queryRecords(request);
        }
        WebPageRequest webPage = request.pageOrDefault();
        PageRequest pageRequest = PageRequest.of(webPage.pageNum(), webPage.pageSize());
        Criteria criteria = queryCriteria(request);
        Set<String> projectionFields = projectionFields(DynamicWebRequest.moduleAlias(), request);
        ProjectionQueryDescriptor projectionDescriptor = projectionListQueryDescriptor(projectionFields);
        Sort[] sorts = querySorts(request, projectionDescriptor.sortableFields());
        PageResult<DynamicRecord> projectedPage = projectionDescriptor.supported()
                ? queryProjectionRecords(projectionFields, criteria, pageRequest, sorts)
                : null;
        if (projectedPage != null) {
            return projectedPage;
        }
        PageResult<DynamicRecord> page = service().pageQuery(criteria, pageRequest, sorts);
        Set<String> fields = projectionFields;
        List<DynamicRecord> records = page.getRecords().stream()
                .map(record -> project(record, fields))
                .toList();
        return PageResult.of(records, page.getTotal(), PageRequest.of(page.getPageNum(), page.getPageSize()));
    }

    private Sort[] querySorts(WebQueryRequest request, Set<String> additionalSortableFields) {
        if (request == null || request.sorts().isEmpty()) {
            return new Sort[0];
        }
        DynamicWebQueryFieldSupport.validatePhysicalSorts(service(), request.sorts(), additionalSortableFields);
        return DynamicWebQueryMapper.sorts(request.sorts());
    }

    private ProjectionQueryDescriptor projectionListQueryDescriptor(Set<String> projectionFields) {
        if (projectionFields == null || projectionFields.isEmpty()) {
            return ProjectionQueryDescriptor.unsupported(DynamicWebRequest.moduleAlias(),
                    "dynamic_ui_config_list", Set.of(),
                    ProjectionQueryFallbackReason.MISSING_PROJECTION);
        }
        return dynamicRelationProjectionReadService.describeListQuery(
                DynamicWebRequest.moduleAlias(),
                recordService,
                projectionFields);
    }

    private PageResult<DynamicRecord> queryProjectionRecords(Set<String> projectionFields,
                                                             Criteria criteria,
                                                             PageRequest pageRequest,
                                                             Sort... sorts) {
        return dynamicRelationProjectionReadService.queryList(
                DynamicWebRequest.moduleAlias(),
                recordService,
                projectionFields,
                criteria,
                pageRequest,
                sorts
        ).orElse(null);
    }

    @Override
    public List<DynamicRecord> queryListRecords(WebQueryRequest request) {
        List<DynamicRecord> records = CrudWeb.super.queryListRecords(request);
        if (request == null || !hasText(request.uiConfigId())) {
            return records;
        }
        Set<String> projectionFields = projectionFields(DynamicWebRequest.moduleAlias(), request);
        return records.stream()
                .map(record -> project(record, projectionFields))
                .toList();
    }

    @Override
    @PostMapping("/query")
    @ActionEndpoint(PlatformAction.QUERY)
    public WebPageResponse<DynamicRecord> query(@RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> {
            if (request != null && request.unpagedEnabled()) {
                List<DynamicRecord> records = WebOutputSupport.records(
                        service(), queryListRecords(request), FieldOutputContext.LIST);
                return WebPageResponse.fromList(records);
            }
            PageResult<DynamicRecord> page = queryRecords(request);
            PageResult<DynamicRecord> output = WebOutputSupport.page(service(), page, FieldOutputContext.LIST);
            return WebPageResponse.from(output, navigationContext(request, output));
        });
    }

    @PostMapping("/query/summary")
    @ActionEndpoint(PlatformAction.QUERY)
    public List<DynamicSummaryItem> querySummary(@RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> {
            List<DynamicSummaryConfigItem> items = summaryConfigItems(DynamicWebRequest.moduleAlias(), request);
            if (items.isEmpty()) {
                return List.of();
            }
            Map<String, FieldDefinition> fields = service().newRecord().getEntity().fields().stream()
                    .collect(java.util.stream.Collectors.toMap(FieldDefinition::fieldName, field -> field));
            Criteria criteria = queryCriteria(request);
            long total = service().count(criteria);
            if (total > SUMMARY_MAX_RECORDS) {
                throw new PlatformException("Summary panel query exceeds max records: " + SUMMARY_MAX_RECORDS);
            }
            List<DynamicRecord> records = total == 0
                    ? List.of()
                    : service().list(criteria, new PageRequest(0, (int) total));
            return items.stream()
                    .map(item -> summaryItem(DynamicWebRequest.moduleAlias(), records, fields, item))
                    .toList();
        });
    }

    @GetMapping("/associations/relation-overview")
    @ActionEndpoint(PlatformAction.VIEW)
    public DynamicAssociationRelationOverview associationRelationOverview() {
        return webScope(() -> recordService.associationRelationOverview(DynamicWebRequest.moduleAlias()));
    }

    @GetMapping("/associations/design")
    @ActionEndpoint(PlatformAction.VIEW)
    public List<DynamicAssociationViewDescriptor> associationDesignDescriptors() {
        return webScope(() -> recordService.associationViewDesignDescriptors(DynamicWebRequest.moduleAlias()));
    }

    @PostMapping("/view/{id}/associations/{viewCode}/diagnose")
    @ActionEndpoint(PlatformAction.QUERY)
    public DynamicAssociationViewDiagnosis diagnoseAssociation(@PathVariable String id,
                                                               @PathVariable String viewCode,
                                                               @RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> {
            String moduleAlias = DynamicWebRequest.moduleAlias();
            String entityAlias = mainEntityAlias(moduleAlias);
            DynamicAssociationViewDescriptor view = recordService.associationView(moduleAlias, entityAlias, viewCode);
            Criteria criteria = targetQueryCriteria(view.targetModuleAlias(), view.targetEntityAlias(), request);
            return recordService.diagnoseAssociationView(moduleAlias, entityAlias, id, viewCode, criteria);
        });
    }

    @PostMapping("/view/{id}/associations/{viewCode}/query")
    @ActionEndpoint(PlatformAction.QUERY)
    public WebPageResponse<DynamicRecord> queryAssociation(@PathVariable String id,
                                                           @PathVariable String viewCode,
                                                           @RequestBody(required = false) WebQueryRequest request) {
        return webScope(() -> {
            String moduleAlias = DynamicWebRequest.moduleAlias();
            String entityAlias = mainEntityAlias(moduleAlias);
            DynamicAssociationViewDescriptor view = recordService.associationView(moduleAlias, entityAlias, viewCode);
            Criteria criteria = targetQueryCriteria(view.targetModuleAlias(), view.targetEntityAlias(), request);
            WebQueryRequest normalized = request == null ? new WebQueryRequest(null, List.of(), List.of()) : request;
            DynamicEntityOperations targetOperations = recordService.entity(view.targetModuleAlias(), view.targetEntityAlias());
            DynamicWebQueryFieldSupport.validatePhysicalSorts(targetOperations, normalized.sorts());
            PageResult<DynamicRecord> page = recordService.associationViewPage(moduleAlias, entityAlias, id,
                    viewCode, criteria, DynamicWebQueryMapper.page(normalized.pageOrDefault()),
                    DynamicWebQueryMapper.sorts(normalized.sorts()));
            return WebPageResponse.from(WebOutputSupport.page(targetOperations, page, FieldOutputContext.LIST));
        });
    }

    @Override
    @PostMapping("/insert")
    @ActionEndpoint(PlatformAction.CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public DynamicRecord insert(@RequestBody DynamicRecord normalized) {
        return webScope(() -> {
            validateWritableSaveFields(normalized, "");
            validateUiSave(DynamicWebRequest.moduleAlias(), normalized);
            String id = service().insert(normalized);
            syncAttachmentsIfPresent(DynamicWebRequest.moduleAlias(), id, normalized);
            return WebOutputSupport.record(service(), service().select(id), FieldOutputContext.VIEW);
        });
    }

    @Override
    @PostMapping("/update/{id}")
    @ActionEndpoint(PlatformAction.UPDATE)
    @Transactional
    public DynamicRecord update(@PathVariable String id,
                                @RequestBody DynamicRecord normalized) {
        return webScope(() -> {
            normalized.setId(id);
            validateWritableSaveFields(normalized, "");
            validateUiSave(DynamicWebRequest.moduleAlias(), normalized);
            requireDataScopeRecord(PlatformAction.UPDATE, id);
            service().update(normalized);
            syncAttachmentsIfPresent(DynamicWebRequest.moduleAlias(), id, normalized);
            return WebOutputSupport.record(service(), selectForAction(PlatformAction.VIEW, id),
                    FieldOutputContext.VIEW);
        });
    }

    @PostMapping("/view/{id}/attachments/query")
    @ActionEndpoint(PlatformAction.VIEW)
    public List<RecordAttachment> queryAttachments(@PathVariable String id) {
        return webScope(() -> {
            requireAttachmentService();
            requireDataScopeRecord(PlatformAction.VIEW, id);
            return recordAttachmentService.listByRecord(DynamicWebRequest.moduleAlias(), id);
        });
    }

    @PostMapping("/view/{id}/attachments/add")
    @ActionEndpoint(PlatformAction.UPDATE)
    public List<RecordAttachment> addAttachment(@PathVariable String id,
                                                @RequestBody RecordAttachmentCommand command) {
        return webScope(() -> {
            requireAttachmentService();
            requireDataScopeRecord(PlatformAction.UPDATE, id);
            recordAttachmentService.add(DynamicWebRequest.moduleAlias(), id, command);
            return recordAttachmentService.listByRecord(DynamicWebRequest.moduleAlias(), id);
        });
    }

    @PostMapping("/view/{id}/attachments/upload-ticket")
    @ActionEndpoint(PlatformAction.UPDATE)
    public RecordAttachmentAccess issueAttachmentUploadTicket(@PathVariable String id) {
        return webScope(() -> {
            requireAttachmentAccessService();
            requireDataScopeRecord(PlatformAction.UPDATE, id);
            return recordAttachmentAccessService.issueUploadAccess(DynamicWebRequest.moduleAlias(), id);
        });
    }

    @PostMapping("/view/{id}/attachments/{attachmentId}/preview-ticket")
    @ActionEndpoint(PlatformAction.VIEW)
    public RecordAttachmentAccess issueAttachmentPreviewTicket(@PathVariable String id,
                                                              @PathVariable String attachmentId) {
        return webScope(() -> {
            requireAttachmentService();
            requireAttachmentAccessService();
            requireDataScopeRecord(PlatformAction.VIEW, id);
            RecordAttachment attachment = recordAttachmentService.requireAttachment(
                    DynamicWebRequest.moduleAlias(), id, attachmentId);
            return recordAttachmentAccessService.issuePreviewAccess(DynamicWebRequest.moduleAlias(), id, attachment);
        });
    }

    @PostMapping("/view/{id}/attachments/{attachmentId}/download-ticket")
    @ActionEndpoint(PlatformAction.VIEW)
    public RecordAttachmentAccess issueAttachmentDownloadTicket(@PathVariable String id,
                                                               @PathVariable String attachmentId) {
        return webScope(() -> {
            requireAttachmentService();
            requireAttachmentAccessService();
            requireDataScopeRecord(PlatformAction.VIEW, id);
            RecordAttachment attachment = recordAttachmentService.requireAttachment(
                    DynamicWebRequest.moduleAlias(), id, attachmentId);
            return recordAttachmentAccessService.issueDownloadAccess(DynamicWebRequest.moduleAlias(), id, attachment);
        });
    }

    @PostMapping("/view/{id}/attachments/update/{attachmentId}")
    @ActionEndpoint(PlatformAction.UPDATE)
    public List<RecordAttachment> updateAttachment(@PathVariable String id,
                                                   @PathVariable String attachmentId,
                                                   @RequestBody RecordAttachmentCommand command) {
        return webScope(() -> {
            requireAttachmentService();
            requireDataScopeRecord(PlatformAction.UPDATE, id);
            recordAttachmentService.updateAttachment(DynamicWebRequest.moduleAlias(), id, attachmentId, command);
            return recordAttachmentService.listByRecord(DynamicWebRequest.moduleAlias(), id);
        });
    }

    @PostMapping("/view/{id}/attachments/delete/{attachmentId}")
    @ActionEndpoint(PlatformAction.UPDATE)
    public List<RecordAttachment> deleteAttachment(@PathVariable String id,
                                                   @PathVariable String attachmentId) {
        return webScope(() -> {
            requireAttachmentService();
            requireDataScopeRecord(PlatformAction.UPDATE, id);
            return recordAttachmentService.deleteAttachment(DynamicWebRequest.moduleAlias(), id, attachmentId);
        });
    }

    private void syncAttachmentsIfPresent(String moduleAlias, String recordId, DynamicRecord record) {
        if (!record.mutationMetadata().containsKey("attachments")) {
            return;
        }
        requireAttachmentService();
        recordAttachmentService.replaceRecordAttachments(moduleAlias, recordId,
                attachmentCommands(record.mutationMetadata().get("attachments")));
    }

    private List<RecordAttachmentCommand> attachmentCommands(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> values)) {
            throw new PlatformException("dynamic record attachments must be array");
        }
        List<RecordAttachmentCommand> commands = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new PlatformException("dynamic record attachment must be object");
            }
            commands.add(new RecordAttachmentCommand(
                    text(map.get("id")),
                    text(map.get("fileId")),
                    text(map.get("displayName")),
                    intValue(map.get("sort")),
                    text(map.get("remark"))
            ));
        }
        return commands;
    }

    private void requireAttachmentService() {
        if (recordAttachmentService == null) {
            throw new PlatformException("record attachment service is not configured");
        }
    }

    private void requireAttachmentAccessService() {
        if (recordAttachmentAccessService == null) {
            throw new PlatformException("record attachment access service is not configured");
        }
    }

    private DynamicRecord selectForAction(PlatformAction action, String id) {
        Object operations = service();
        if (operations instanceof DataScopeAbility<?> dataScopeAbility) {
            return selectFromDataScope(dataScopeAbility, action, id);
        }
        return service().select(id);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private DynamicRecord selectFromDataScope(DataScopeAbility dataScopeAbility, PlatformAction action, String id) {
        return (DynamicRecord) dataScopeAbility.selectForAction(action, id);
    }

    private List<DynamicSummaryConfigItem> summaryConfigItems(String moduleAlias, WebQueryRequest request) {
        if (request == null || !hasText(request.uiConfigId())) {
            return List.of();
        }
        requireLowCodePageServices();
        PlatformPageConfigSnapshot snapshot = pageConfigSnapshotService.snapshot(moduleAlias);
        PlatformUiConfig uiConfig = snapshot.uiConfigs().stream()
                .filter(config -> Objects.equals(config.getId(), request.uiConfigId()))
                .findFirst()
                .orElseThrow(() -> new PlatformException("UI config is not published in module snapshot: "
                        + request.uiConfigId()));
        requireListUiConfig(snapshot, uiConfig);
        if (!hasText(uiConfig.getLayoutJson())) {
            return List.of();
        }
        JsonNode items = summaryItemsNode(uiConfig);
        if (!items.isArray()) {
            return List.of();
        }
        List<DynamicSummaryConfigItem> configItems = new ArrayList<>();
        for (JsonNode item : items) {
            String detailId = text(item, "detailId");
            if (!hasText(detailId)) {
                detailId = text(item, "moduleMetadataFieldId");
            }
            configItems.add(new DynamicSummaryConfigItem(
                    detailId,
                    text(item, "calcType"),
                    text(item, "label"),
                    item.hasNonNull("precision") ? item.get("precision").asInt() : null,
                    text(item, "formatter")
            ));
        }
        return configItems;
    }

    private void requireListUiConfig(PlatformPageConfigSnapshot snapshot, PlatformUiConfig uiConfig) {
        PlatformUiSet uiSet = snapshot.uiSets().stream()
                .filter(set -> Objects.equals(set.getId(), uiConfig.getUiSetId()))
                .findFirst()
                .orElseThrow(() -> new PlatformException("UI config set is not published in module snapshot: "
                        + uiConfig.getUiSetId()));
        if (uiSet.getSetType() != PlatformUiSetType.LIST) {
            throw new PlatformException("Summary panel requires LIST UI config: " + uiConfig.getId());
        }
    }

    private JsonNode summaryItemsNode(PlatformUiConfig uiConfig) {
        try {
            return JSON.readTree(uiConfig.getLayoutJson())
                    .path("summaryPanel")
                    .path("items");
        } catch (JsonProcessingException exception) {
            throw new PlatformException("UI config layout JSON cannot be decoded: " + uiConfig.getId());
        }
    }

    private DynamicSummaryItem summaryItem(String moduleAlias,
                                           List<DynamicRecord> records,
                                           Map<String, FieldDefinition> fields,
                                           DynamicSummaryConfigItem item) {
        if (!hasText(item.detailId()) || !hasText(item.calcType())) {
            return emptySummaryItem(item, item.label());
        }
        try {
            ResolvedModuleMetadataField resolved = moduleMetadataFieldService.resolve(item.detailId());
            String label = hasText(item.label()) ? item.label() : resolved.fieldTitle();
            if (!Objects.equals(resolved.moduleAlias(), moduleAlias)
                    || resolved.relationRole() != RelationRole.MAIN) {
                return emptySummaryItem(item, label);
            }
            FieldDefinition field = fields.get(resolved.fieldName());
            if (field == null || !summaryCalcSupported(item.calcType(), field.type())) {
                return emptySummaryItem(item, label);
            }
            return new DynamicSummaryItem(
                    item.detailId(),
                    item.calcType(),
                    label,
                    item.precision(),
                    item.formatter(),
                    DynamicWebValues.webValue(summaryValue(records, resolved.fieldName(), field.type(), item.calcType()))
            );
        } catch (RuntimeException exception) {
            return emptySummaryItem(item, item.label());
        }
    }

    private DynamicSummaryItem emptySummaryItem(DynamicSummaryConfigItem item, String label) {
        return new DynamicSummaryItem(
                item.detailId(),
                item.calcType(),
                label,
                item.precision(),
                item.formatter(),
                null
        );
    }

    private Object summaryValue(List<DynamicRecord> records, String fieldName, FieldType fieldType, String calcType) {
        String normalized = normalizedCalcType(calcType);
        List<Object> values = records.stream()
                .map(record -> record.getValues().get(fieldName))
                .filter(this::presentSummaryValue)
                .toList();
        return switch (normalized) {
            case "sum" -> values.stream()
                    .map(this::decimalValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            case "avg" -> values.isEmpty()
                    ? null
                    : values.stream()
                    .map(this::decimalValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(values.size()), MathContext.DECIMAL64);
            case "max" -> comparableValue(values, fieldType, Comparator.naturalOrder());
            case "min" -> comparableValue(values, fieldType, Comparator.reverseOrder());
            case "count" -> (long) values.size();
            case "distinctcount" -> values.stream().distinct().count();
            default -> null;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object comparableValue(List<Object> values, FieldType fieldType, Comparator<Comparable> comparator) {
        if (values.isEmpty()) {
            return null;
        }
        return values.stream()
                .filter(Comparable.class::isInstance)
                .map(Comparable.class::cast)
                .sorted(comparator.reversed())
                .findFirst()
                .orElse(null);
    }

    private boolean summaryCalcSupported(String calcType, FieldType fieldType) {
        String normalized = normalizedCalcType(calcType);
        if ("count".equals(normalized) || "distinctcount".equals(normalized)) {
            return true;
        }
        if ("sum".equals(normalized) || "avg".equals(normalized)) {
            return numericField(fieldType);
        }
        if ("max".equals(normalized) || "min".equals(normalized)) {
            return numericField(fieldType)
                    || fieldType == FieldType.DATE
                    || fieldType == FieldType.TIMESTAMP
                    || fieldType == FieldType.ZONED_TIMESTAMP;
        }
        return false;
    }

    private boolean numericField(FieldType fieldType) {
        return fieldType == FieldType.INTEGER
                || fieldType == FieldType.LONG
                || fieldType == FieldType.DECIMAL;
    }

    private String normalizedCalcType(String calcType) {
        return calcType == null ? "" : calcType.replace("_", "").toLowerCase();
    }

    private boolean presentSummaryValue(Object value) {
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isBlank() ? null : text;
    }

    private Criteria quickSearchCriteria(String moduleAlias, WebQueryRequest request) {
        if (request == null || !hasText(request.quickSearch())) {
            return Criteria.of();
        }
        if (!hasText(request.uiConfigId())) {
            throw new PlatformException("Quick search requires published LIST uiConfigId");
        }
        String keyword = request.quickSearch().trim();
        List<String> fields = quickSearchFields(moduleAlias, request);
        if (fields.isEmpty()) {
            throw new PlatformException("Quick search requires at least one searchable field");
        }
        Criteria criteria = Criteria.of();
        criteria.andGroup(group -> {
            for (String field : fields) {
                group.or(field, CriteriaOperator.LIKE, keyword);
            }
        });
        return criteria;
    }

    private List<String> quickSearchFields(String moduleAlias, WebQueryRequest request) {
        requireLowCodePageServices();
        PlatformPageConfigSnapshot snapshot = pageConfigSnapshotService.snapshot(moduleAlias);
        PlatformUiConfig uiConfig = publishedUiConfig(snapshot, request.uiConfigId());
        requireListUiConfig(snapshot, uiConfig);
        Set<String> visibleFields = new LinkedHashSet<>();
        for (PlatformUiConfigField field : snapshot.uiFields()) {
            if (!Objects.equals(field.getUiConfigId(), uiConfig.getId())
                    || !Boolean.TRUE.equals(field.getVisible())) {
                continue;
            }
            ResolvedModuleMetadataField resolved = moduleMetadataFieldService.resolve(field.getModuleMetadataFieldId());
            if (DynamicWebQueryFieldSupport.searchableTextField(resolved)) {
                visibleFields.add(resolved.fieldName());
            }
        }
        if (request.quickSearchFields().isEmpty()) {
            return List.copyOf(visibleFields);
        }
        List<String> requestedFields = request.quickSearchFields().stream()
                .filter(this::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        for (String field : requestedFields) {
            if (!visibleFields.contains(field)) {
                throw new PlatformException("Quick search field is not searchable in UI config: " + field);
            }
        }
        return requestedFields;
    }

    private List<String> quickSearchFieldsForSchema(String uiConfigId) {
        if (!hasText(uiConfigId)) {
            return List.of();
        }
        return quickSearchFields(DynamicWebRequest.moduleAlias(),
                new WebQueryRequest(null, null, List.of(), null, Map.of(), List.of(),
                        uiConfigId, null, Map.of(), null, null, List.of(), null));
    }

    private void requireDataScopeRecord(PlatformAction action, String id) {
        Object operations = service();
        if (operations instanceof DataScopeAbility<?> dataScopeAbility) {
            requireRecordScope(dataScopeAbility, actionPolicy(action), id);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void requireRecordScope(DataScopeAbility dataScopeAbility, ActionExecutionPolicy policy, String id) {
        dataScopeAbility.requireRecordScope(policy, java.util.List.of(id));
    }

    private ActionExecutionPolicy actionPolicy(PlatformAction fallback) {
        return ActionExecutionContextHolder.current()
                .filter(context -> context.moduleAlias().equals(webScopeName()))
                .map(ActionExecutionContext::actionPolicy)
                .orElseGet(fallback::executionPolicy);
    }

    private Set<String> projectionFields(String moduleAlias, WebQueryRequest request) {
        requireLowCodePageServices();
        PlatformPageConfigSnapshot snapshot = pageConfigSnapshotService.snapshot(moduleAlias);
        PlatformUiConfig uiConfig = publishedUiConfig(snapshot, request.uiConfigId());
        Set<String> fields = new LinkedHashSet<>();
        for (PlatformUiConfigField field : snapshot.uiFields()) {
            if (!Objects.equals(field.getUiConfigId(), uiConfig.getId())
                    || !Boolean.TRUE.equals(field.getVisible())) {
                continue;
            }
            ResolvedModuleMetadataField resolved = moduleMetadataFieldService.resolve(field.getModuleMetadataFieldId());
            if (resolved.relationRole() != RelationRole.MAIN) {
                throw new PlatformException("List UI config only supports main relation fields: "
                        + field.getModuleMetadataFieldId());
            }
            fields.add(resolved.fieldName());
        }
        return fields;
    }

    private Criteria targetQueryCriteria(String moduleAlias, String entityAlias, WebQueryRequest request) {
        Criteria templateCriteria = Criteria.of();
        if (request != null && hasText(request.queryTemplateId())) {
            requireLowCodeQueryServices();
            validateQueryTemplateBelongsToModule(moduleAlias, request.queryTemplateId());
            templateCriteria = queryItemService.compile(request.queryTemplateId(), request.externalQueryValues());
        }
        Criteria manualCriteria = request == null || request.conditions().isEmpty()
                ? Criteria.of()
                : criteria(moduleAlias, entityAlias, request.conditions());
        Criteria treeCriteria = request == null || request.criteria() == null
                ? Criteria.of()
                : criteria(moduleAlias, entityAlias, request.criteria());
        Criteria queryFormCriteria = DynamicWebQueryFormSupport.queryFormCriteria(moduleAlias,
                request, pageConfigSnapshotService, moduleMetadataFieldService, fieldUiControlService,
                fieldUiControlBindingService,
                conditions -> recordService.queryCriteria(moduleAlias, entityAlias, conditions));
        Criteria quickCriteria = quickSearchCriteria(moduleAlias, request);
        return andCriteria(templateCriteria, queryFormCriteria, manualCriteria, treeCriteria, quickCriteria);
    }

    private void validateUiSave(String moduleAlias, DynamicRecord record) {
        Object uiConfigIdValue = record.mutationMetadata().get("uiConfigId");
        if (!(uiConfigIdValue instanceof String uiConfigId) || !hasText(uiConfigId)) {
            return;
        }
        requireLowCodePageServices();
        PlatformPageConfigSnapshot snapshot = pageConfigSnapshotService.snapshot(moduleAlias);
        PlatformUiConfig uiConfig = snapshot.uiConfigs().stream()
                .filter(config -> Objects.equals(config.getId(), uiConfigId))
                .findFirst()
                .orElseThrow(() -> PlatformErrors.config(PlatformErrorCodes.CONFIG_MISSING,
                        "UI config is not published in module snapshot: " + uiConfigId,
                        ErrorScope.module(moduleAlias)));
        Map<String, FieldDefinition> mainFields = record.getEntity().fields().stream()
                .collect(java.util.stream.Collectors.toMap(FieldDefinition::fieldName, field -> field));
        for (PlatformUiConfigField uiField : snapshot.uiFields()) {
            if (!Objects.equals(uiField.getUiConfigId(), uiConfig.getId())
                    || !Boolean.TRUE.equals(uiField.getVisible())) {
                continue;
            }
            ResolvedModuleMetadataField resolved = moduleMetadataFieldService.resolve(uiField.getModuleMetadataFieldId());
            if (resolved.relationRole() == RelationRole.MAIN) {
                validateUiRecordField(moduleAlias, record, uiField, mainFields.get(resolved.fieldName()),
                        resolved.fieldName());
            } else if (resolved.relationRole() == RelationRole.CHILD) {
                validateUiChildField(moduleAlias, record, uiField, resolved);
            }
        }
    }

    private void validateUiChildField(String moduleAlias,
                                      DynamicRecord record,
                                      PlatformUiConfigField uiField,
                                      ResolvedModuleMetadataField resolved) {
        List<DynamicRecord> rows = record.getChildren(resolved.relationAlias());
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            DynamicRecord row = rows.get(rowIndex);
            Map<String, FieldDefinition> fields = row.getEntity().fields().stream()
                    .collect(java.util.stream.Collectors.toMap(FieldDefinition::fieldName, field -> field));
            validateUiRecordField(moduleAlias, row, uiField, fields.get(resolved.fieldName()),
                    resolved.relationAlias() + "." + resolved.fieldName(), rowIndex);
        }
    }

    private void validateUiRecordField(String moduleAlias,
                                       DynamicRecord record,
                                       PlatformUiConfigField uiField,
                                       FieldDefinition field,
                                       String fieldPath) {
        validateUiRecordField(moduleAlias, record, uiField, field, fieldPath, null);
    }

    private void validateUiRecordField(String moduleAlias,
                                       DynamicRecord record,
                                       PlatformUiConfigField uiField,
                                       FieldDefinition field,
                                       String fieldPath,
                                       Integer rowIndex) {
        if (field == null) {
            return;
        }
        validateUiReadOnly(moduleAlias, record, uiField, field, fieldPath, rowIndex);
        validateUiRequired(moduleAlias, record, uiField, field, fieldPath, rowIndex);
    }

    private void validateUiReadOnly(String moduleAlias,
                                    DynamicRecord record,
                                    PlatformUiConfigField uiField,
                                    FieldDefinition field,
                                    String fieldPath,
                                    Integer rowIndex) {
        if (Boolean.TRUE.equals(uiField.getReadOnly()) && record.isExplicitlySet(field.fieldName())) {
            throw PlatformErrors.validation(PlatformErrorCodes.VALIDATION_FAILED,
                    "UI read-only field cannot be saved: " + fieldPath, uiFieldTarget(moduleAlias, fieldPath, rowIndex));
        }
    }

    private void validateUiRequired(String moduleAlias,
                                    DynamicRecord record,
                                    PlatformUiConfigField uiField,
                                    FieldDefinition field,
                                    String fieldPath,
                                    Integer rowIndex) {
        boolean required = Boolean.TRUE.equals(uiField.getRequiredOverride()) || field.isRequired();
        if (!required) {
            return;
        }
        Object value = record.getValues().get(field.fieldName());
        if (value == null || value instanceof String text && text.isBlank()) {
            throw PlatformErrors.validation(PlatformErrorCodes.VALIDATION_FAILED,
                    "UI required field is missing: " + fieldPath, uiFieldTarget(moduleAlias, fieldPath, rowIndex));
        }
    }

    private ErrorTarget uiFieldTarget(String moduleAlias, String fieldPath, Integer rowIndex) {
        int separator = fieldPath.indexOf('.');
        if (separator < 0) {
            return ErrorTarget.field(fieldPath).module(moduleAlias);
        }
        ErrorTarget target = ErrorTarget.field(fieldPath.substring(separator + 1))
                .module(moduleAlias)
                .relation(fieldPath.substring(0, separator));
        return rowIndex == null ? target : target.row(rowIndex);
    }

    private void validateQueryTemplateBelongsToModule(String moduleAlias, String queryTemplateId) {
        if (!hasText(queryTemplateId)) {
            return;
        }
        requireLowCodeQueryServices();
        PlatformPageConfigSnapshot snapshot = pageConfigSnapshotService.snapshot(moduleAlias);
        PlatformQueryTemplate template = snapshot.queryTemplates().stream()
                .filter(item -> Objects.equals(item.getId(), queryTemplateId))
                .findFirst()
                .orElseThrow(() -> new PlatformException("Query template is not published or enabled in module snapshot: "
                        + queryTemplateId));
        if (!Objects.equals(template.getModuleAlias(), moduleAlias)) {
            throw new PlatformException("Query template must belong to module: " + moduleAlias);
        }
    }

    private void validateReferenceUiContexts(String sourceModuleAlias,
                                             DynamicReferenceDescriptor reference,
                                             DynamicWebReferenceRequest request) {
        if (request == null || (!hasText(request.sourceUiConfigId())
                && !hasText(request.uiConfigId())
                && !hasText(request.queryTemplateId()))) {
            return;
        }
        if (hasText(request.sourceUiConfigId())) {
            requireLowCodePageServices();
            PlatformPageConfigSnapshot sourceSnapshot = pageConfigSnapshotService.snapshot(sourceModuleAlias);
            PlatformUiConfig sourceConfig = publishedUiConfig(sourceSnapshot, request.sourceUiConfigId());
            requireUiConfigTypes(sourceSnapshot, sourceConfig, Set.of(PlatformUiSetType.FORM, PlatformUiSetType.DETAIL),
                    "Reference source UI config");
        }
        if (hasText(request.uiConfigId())) {
            requireLowCodePageServices();
            PlatformPageConfigSnapshot targetSnapshot = pageConfigSnapshotService.snapshot(reference.targetModuleAlias());
            PlatformUiConfig targetConfig = publishedUiConfig(targetSnapshot, request.uiConfigId());
            requireUiConfigTypes(targetSnapshot, targetConfig, Set.of(PlatformUiSetType.LIST, PlatformUiSetType.REFERENCE),
                    "Reference target UI config");
        }
        if (hasText(request.queryTemplateId())) {
            validateQueryTemplateBelongsToModule(reference.targetModuleAlias(), request.queryTemplateId());
        }
    }

    private PlatformUiConfig publishedUiConfig(PlatformPageConfigSnapshot snapshot, String uiConfigId) {
        return snapshot.uiConfigs().stream()
                .filter(config -> Objects.equals(config.getId(), uiConfigId))
                .findFirst()
                .orElseThrow(() -> new PlatformException("UI config is not published in module snapshot: "
                        + uiConfigId));
    }

    private void requireUiConfigTypes(PlatformPageConfigSnapshot snapshot,
                                      PlatformUiConfig uiConfig,
                                      Set<PlatformUiSetType> allowedTypes,
                                      String label) {
        PlatformUiSet uiSet = snapshot.uiSets().stream()
                .filter(set -> Objects.equals(set.getId(), uiConfig.getUiSetId()))
                .findFirst()
                .orElseThrow(() -> new PlatformException("UI config set is not published in module snapshot: "
                        + uiConfig.getUiSetId()));
        if (!allowedTypes.contains(uiSet.getSetType())) {
            throw new PlatformException(label + " type is not allowed: " + uiSet.getSetType());
        }
    }

    private DynamicRecord project(DynamicRecord source, Set<String> fields) {
        DynamicRecord projected = new DynamicRecord(source.getEntity());
        projected.setId(source.getId());
        projected.setTenantId(source.getTenantId());
        projected.setVersion(source.getVersion());
        Map<String, FieldDefinition> fieldDefinitions = source.getEntity().fields().stream()
                .collect(java.util.stream.Collectors.toMap(FieldDefinition::fieldName, field -> field));
        for (String field : fields) {
            Map<String, Object> values = source.getValues();
            if (values.containsKey(field)) {
                FieldDefinition definition = fieldDefinitions.get(field);
                if (definition != null && !definition.isPhysical()) {
                    projected.putDisplayValue(field, values.get(field));
                } else {
                    projected.setValue(field, values.get(field));
                }
            }
        }
        return projected;
    }

    private void requireLowCodePageServices() {
        if (pageConfigSnapshotService == null || moduleMetadataFieldService == null) {
            throw new PlatformException("dynamic low-code page services are not configured");
        }
    }

    private void requireLowCodeQueryServices() {
        if (pageConfigSnapshotService == null || queryItemService == null) {
            throw new PlatformException("dynamic low-code query services are not configured");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            throw new PlatformException("dynamic record attachment sort must be number", ex);
        }
    }

    @Override
    @PostMapping("/sort/{id}")
    @ActionEndpoint(PlatformAction.SORT)
    public int sort(HttpServletRequest httpRequest,
                                 @PathVariable String id,
                                 @RequestBody(required = false) TreeSortWebRequest request) {
        return webScope(() -> {
            TreeSortWebRequest normalized = request == null ? new TreeSortWebRequest(null, null, null) : request;
            DynamicEntityOperations operations = service();
            Set<String> capabilities = operations.describe().capabilities();
            if (capabilities.contains(EntityCapability.TREE.name())) {
                requireSortInput(normalized);
                operations.moveInTree(id, normalized.previousId(), normalized.nextId(), normalized.parentId());
                return 1;
            }
            if (!capabilities.contains(EntityCapability.SORT.name())) {
                throw new PlatformException("dynamic entity does not support capability: SORT");
            }
            if (normalized.parentId() != null && !normalized.parentId().isBlank()) {
                throw new IllegalArgumentException("sort parentId requires TREE capability");
            }
            if (normalized.previousId() != null && !normalized.previousId().isBlank()) {
                operations.moveAfter(id, normalized.previousId());
                return 1;
            }
            if (normalized.nextId() != null && !normalized.nextId().isBlank()) {
                operations.moveBefore(id, normalized.nextId());
                return 1;
            }
            throw new IllegalArgumentException("sort requires previousId or nextId");
        });
    }

    @GetMapping("/describe")
    @ActionEndpoint(PlatformAction.VIEW)
    public DynamicModuleDescriptor describeModule(@PathVariable String moduleAlias) {
        return tenantScope(moduleAlias, () -> permissionScopedDescriptor(moduleAlias));
    }

    @GetMapping("/openapi")
    @ActionEndpoint(PlatformAction.VIEW)
    public Map<String, Object> openApi(@PathVariable String moduleAlias) {
        return tenantScope(moduleAlias, () -> OpenApi31Projector.project(openApiGenerator.generate(
                permissionScopedDescriptor(moduleAlias),
                action -> recordService.actionAuthorizationAvailability(
                        moduleAlias, action.code(), Set.of()).available())));
    }

    @GetMapping("/navigation/{sessionId}/{recordId}")
    @ActionEndpoint(PlatformAction.VIEW)
    public PlatformRecordNavigationMove navigation(@PathVariable String sessionId,
                                                   @PathVariable String recordId) {
        return webScope(() -> {
            requireNavigationService();
            PlatformRecordNavigationMove move = navigationService.move(DynamicWebRequest.moduleAlias(), sessionId, recordId);
            requireNavigationViewScope(move.currentRecordId());
            requireNavigationViewScope(move.previousRecordId());
            requireNavigationViewScope(move.nextRecordId());
            return move;
        });
    }

    @PostMapping("/code/preview")
    @ActionEndpoint(PlatformAction.CREATE)
    public List<CodeBusinessPreviewItem> previewCode(@PathVariable String moduleAlias,
                                                     @RequestBody(required = false) DynamicRecord record) {
        return tenantScope(moduleAlias, () -> {
            if (codeBusinessPreviewService == null) {
                throw new PlatformException("code business preview service is not configured");
            }
            DynamicRecord normalized = record == null ? service().newRecord() : record;
            return codeBusinessPreviewService.preview(
                    moduleAlias,
                    mainEntityAlias(moduleAlias),
                    normalized.getValues(),
                    resolveOrganizationId(normalized),
                    null,
                    null
            );
        });
    }

    @PostMapping("/formula/preview")
    @ActionEndpoint(PlatformAction.CREATE)
    public DynamicFormulaPreviewResponse previewFormula(@PathVariable String moduleAlias,
                                                        @RequestBody(required = false) DynamicFormulaPreviewRequest request) {
        return tenantScope(moduleAlias, () -> {
            String entityAlias = mainEntityAlias(moduleAlias);
            DynamicFormulaPreviewRequest normalized = request == null ? new DynamicFormulaPreviewRequest(null) : request;
            DynamicRecord record = record(moduleAlias, entityAlias, normalized.record());
            return DynamicFormulaPreviewResponse.from(recordService.previewFormula(moduleAlias, entityAlias, record));
        });
    }

    @Override
    public List<DynamicActionDescriptor> listActions() {
        String moduleAlias = DynamicWebRequest.moduleAlias();
        return recordService.actions(moduleAlias).stream()
                .filter(action -> recordService.actionAuthorizationAvailability(moduleAlias, action.code(), Set.of()).available())
                .toList();
    }

    @Override
    public DynamicWebActionExecutionResponse executeListAction(String actionCode, DynamicWebActionRequest request) {
        String moduleAlias = DynamicWebRequest.moduleAlias();
        requireActionLevel(moduleAlias, actionCode, Set.of(EntityActionLevel.LIST, EntityActionLevel.ANY),
                "dynamic action does not support list path: ");
        return executeAction(moduleAlias, actionCode, null, request);
    }

    @Override
    public DynamicWebActionExecutionResponse executeBatchAction(String actionCode, DynamicWebActionRequest request) {
        String moduleAlias = DynamicWebRequest.moduleAlias();
        DynamicWebActionRequest normalized = request == null ? DynamicWebActionRequest.empty() : request;
        if (normalized.ids().isEmpty()) {
            throw new IllegalArgumentException("batch action requires ids");
        }
        requireActionLevel(moduleAlias, actionCode, Set.of(EntityActionLevel.BATCH, EntityActionLevel.ANY),
                "dynamic action does not support batch path: ");
        return executeAction(moduleAlias, actionCode, null, normalized);
    }

    @Override
    public DynamicWebActionExecutionResponse executeRecordAction(String actionCode,
                                                                 String recordId,
                                                                 DynamicWebActionRequest request) {
        String moduleAlias = DynamicWebRequest.moduleAlias();
        requireActionLevel(moduleAlias, actionCode, Set.of(EntityActionLevel.RECORD, EntityActionLevel.ANY),
                "dynamic action does not support record path: ");
        return executeAction(moduleAlias, actionCode, recordId, request);
    }

    @PostMapping("/{actionCode}/duplicate/check")
    public RecordDuplicateCheckResult checkDuplicate(@PathVariable String actionCode,
                                                     @RequestBody(required = false) DynamicWebDuplicateCheckRequest request) {
        String moduleAlias = DynamicWebRequest.moduleAlias();
        requireActionLevel(moduleAlias, actionCode, Set.of(EntityActionLevel.RECORD, EntityActionLevel.ANY),
                "dynamic duplicate action must be record action: ");
        return webScope(() -> {
            requireDuplicateCheckService();
            DynamicWebDuplicateCheckRequest normalized = request == null
                    ? DynamicWebDuplicateCheckRequest.empty()
                    : request;
            DynamicActionAvailability availability = recordService.actionAuthorizationAvailability(
                    moduleAlias, actionCode, duplicateRecordIds(normalized));
            if (!availability.available()) {
                throw new PlatformException(hasText(availability.message())
                        ? availability.message()
                        : "dynamic duplicate action is not available: " + actionCode);
            }
            return duplicateCheckService.check(moduleAlias, actionCode, normalized.recordId(), normalized.values());
        });
    }

    private Set<String> duplicateRecordIds(DynamicWebDuplicateCheckRequest request) {
        return request.recordId() == null || request.recordId().isBlank()
                ? Set.of()
                : Set.of(request.recordId().trim());
    }

    @Override
    @PostMapping("/references/{fieldName}/resolve")
    @ActionEndpoint(PlatformAction.REFERENCE)
    public DynamicReferenceResolveResponse reference(@PathVariable String fieldName,
                                                     @RequestBody(required = false) DynamicWebReferenceRequest request) {
        return ReferenceWeb.super.reference(fieldName, request);
    }

    @PostMapping("/references/{fieldName}/generate")
    @ActionEndpoint(PlatformAction.REFERENCE)
    public RecordGenerationResult generateFromReference(@PathVariable String fieldName,
                                                        @RequestBody(required = false) DynamicWebReferenceGenerationRequest request) {
        return webScope(() -> {
            DynamicWebReferenceGenerationRequest normalized = request == null
                    ? new DynamicWebReferenceGenerationRequest(null)
                    : request;
            return referenceGenerationFacade().generateFromReference(
                    DynamicWebRequest.moduleAlias(),
                    mainEntityAlias(DynamicWebRequest.moduleAlias()),
                    fieldName,
                    normalized.sourceRecordId()
            );
        });
    }

    @PostMapping("/generation/confirm")
    @ActionEndpoint(PlatformAction.CREATE)
    public RecordGenerationCommitResult confirmGeneratedDraft(@RequestBody(required = false) DynamicWebGenerationConfirmRequest request) {
        return webScope(() -> {
            DynamicWebGenerationConfirmRequest normalized = request == null
                    ? new DynamicWebGenerationConfirmRequest(null, null, null, null)
                    : request;
            String targetModuleAlias = requireText(normalized.targetModuleAlias(), "targetModuleAlias");
            String targetEntityAlias = requireText(normalized.targetEntityAlias(), "targetEntityAlias");
            requirePathModule(targetModuleAlias);
            DynamicRecord draft = record(targetModuleAlias, targetEntityAlias, normalized.record());
            String id = referenceGenerationFacade().confirmDraft(new RecordGenerationDraft(
                    targetModuleAlias,
                    targetEntityAlias,
                    draft,
                    normalized.originContext()
            ));
            return new RecordGenerationCommitResult(
                    normalized.originContext() == null ? null : normalized.originContext().generationRuleId(),
                    normalized.originContext() == null ? null : normalized.originContext().batchId(),
                    targetModuleAlias,
                    List.of(id)
            );
        });
    }

    private DynamicWebActionExecutionResponse executeAction(String moduleAlias,
                                                            String actionCode,
                                                            String pathRecordId,
                                                            DynamicWebActionRequest request) {
        String entityAlias = recordService.actionEntityAlias(moduleAlias, actionCode);
        return DynamicWebActionExecutionResponse.from(recordService.executeAction(
                moduleAlias, actionCode, actionRequest(moduleAlias, entityAlias, pathRecordId, request)));
    }

    @Override
    public DynamicReferenceResolveResponse resolveReference(String fieldName, DynamicWebReferenceRequest request) {
        String moduleAlias = DynamicWebRequest.moduleAlias();
        String entityAlias = mainEntityAlias(moduleAlias);
        DynamicWebReferenceRequest normalized = request == null ? DynamicWebReferenceRequest.empty() : request;
        DynamicReferenceDescriptor reference = recordService.reference(moduleAlias, entityAlias, fieldName);
        validateReferenceUiContexts(moduleAlias, reference, normalized);
        return recordService.resolveFieldReference(moduleAlias, entityAlias, fieldName, new DynamicReferenceResolveRequest(
                normalized.mode(),
                normalized.matchMode(),
                normalized.fuzzy(),
                normalized.values(),
                referenceCriteria(reference, normalized),
                DynamicWebQueryMapper.page(normalized.page()),
                normalized.includeProjections(),
                normalized.formValues()
        ));
    }

    private Criteria referenceCriteria(DynamicReferenceDescriptor reference,
                                       DynamicWebReferenceRequest request) {
        Criteria templateCriteria = Criteria.of();
        String queryTemplateId = hasText(request.queryTemplateId())
                ? request.queryTemplateId()
                : reference == null ? null : reference.queryTemplateId();
        if (reference != null && hasText(queryTemplateId)) {
            requireLowCodeQueryServices();
            validateQueryTemplateBelongsToModule(reference.targetModuleAlias(), queryTemplateId);
            templateCriteria = queryItemService.compile(queryTemplateId, request.externalQueryValues());
        }
        Criteria manualCriteria = criteria(reference.targetModuleAlias(), reference.targetEntityAlias(),
                request.conditions());
        Criteria treeCriteria = criteria(reference.targetModuleAlias(), reference.targetEntityAlias(),
                request.criteria());
        return andCriteria(templateCriteria, manualCriteria, treeCriteria);
    }

    private ReferenceRecordGenerationFacade referenceGenerationFacade() {
        if (referenceRecordGenerationFacade == null) {
            throw new PlatformException("reference record generation facade is not configured");
        }
        return referenceRecordGenerationFacade;
    }

    private void requireDuplicateCheckService() {
        if (duplicateCheckService == null) {
            throw new PlatformException("record duplicate check service is not configured");
        }
    }

    private void requireNavigationService() {
        if (navigationService == null) {
            throw new PlatformException("record navigation service is not configured");
        }
    }

    private void requireNavigationViewScope(String recordId) {
        if (recordId != null && !recordId.isBlank()) {
            DynamicRecord visible = recordService.select(
                    DynamicWebRequest.moduleAlias(),
                    mainEntityAlias(DynamicWebRequest.moduleAlias()),
                    recordId);
            if (visible == null) {
                throw new PlatformException("record navigation record is not visible: " + recordId);
            }
        }
    }

    private PlatformRecordNavigationContext navigationContext(WebQueryRequest request,
                                                             PageResult<DynamicRecord> page) {
        if (request == null || !request.navigationSessionEnabled() || page.getRecords().isEmpty()) {
            return null;
        }
        requireNavigationService();
        return navigationService.createCurrentUserSession(
                DynamicWebRequest.moduleAlias(),
                mainEntityAlias(DynamicWebRequest.moduleAlias()),
                page.getRecords().stream().map(DynamicRecord::getId).toList(),
                page.getPageNum(),
                page.getPageSize(),
                page.getTotal(),
                request.navigationQueryKey()
        );
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlatformException("dynamic generation confirm requires " + fieldName);
        }
        return value.trim();
    }

    private void requirePathModule(String targetModuleAlias) {
        String pathModuleAlias = DynamicWebRequest.moduleAlias();
        if (!pathModuleAlias.equals(targetModuleAlias)) {
            throw new PlatformException("dynamic generation confirm targetModuleAlias mismatch: "
                    + targetModuleAlias + " != " + pathModuleAlias);
        }
    }

    private DynamicRecord record(String moduleAlias, String entityAlias, DynamicRecordPayload payload) {
        DynamicRecord record = recordService.newRecord(moduleAlias, entityAlias);
        DynamicRecordPayload normalized = payload == null ? DynamicRecordPayload.empty() : payload;
        if (normalized.id() != null && !normalized.id().isBlank()) {
            record.setId(normalized.id());
        }
        if (normalized.version() != null) {
            record.setVersion(normalized.version());
        }
        normalized.values().forEach(record::setValue);
        normalized.children().forEach((relationCode, rows) -> {
            if (rows == null) {
                throw new PlatformException("dynamic child relation must be array: " + relationCode);
            }
            String childEntityAlias = childEntityAlias(moduleAlias, entityAlias, relationCode);
            record.setChildren(
                    relationCode,
                    rows.stream()
                            .map(row -> record(moduleAlias, childEntityAlias, row))
                            .toList()
            );
        });
        return record;
    }

    private void validateWritableSaveFields(DynamicRecord record, String pathPrefix) {
        Map<String, FieldDefinition> fields = record.getEntity().fields().stream()
                .collect(java.util.stream.Collectors.toMap(FieldDefinition::fieldName, field -> field));
        for (String fieldCode : record.explicitFieldCodes()) {
            FieldDefinition field = fields.get(fieldCode);
            if (field != null && !field.isPhysical()) {
                throw new PlatformException("Virtual field cannot be saved: " + pathPrefix + fieldCode);
            }
        }
        record.getChildren().forEach((relationCode, rows) -> {
            if (rows == null) {
                return;
            }
            for (DynamicRecord row : rows) {
                validateWritableSaveFields(row, pathPrefix + relationCode + ".");
            }
        });
    }

    private String childEntityAlias(String moduleAlias, String parentEntityAlias, String relationCode) {
        return recordService.relations(moduleAlias).stream()
                .filter(relation -> relation.parentEntityAlias().equals(parentEntityAlias))
                .filter(relation -> relation.code().equals(relationCode))
                .map(DynamicRelationDescriptor::childEntityAlias)
                .findFirst()
                .orElseThrow(() -> new PlatformException("unknown dynamic child relation: " + relationCode));
    }

    private String mainEntityAlias(String moduleAlias) {
        return recordService.mainEntityAlias(moduleAlias);
    }

    private String resolveOrganizationId(DynamicRecord record) {
        if (record != null && record.getAuthOrganizationId() != null && !record.getAuthOrganizationId().isBlank()) {
            return record.getAuthOrganizationId();
        }
        return CurrentUserContext.currentUser()
                .map(CurrentUser::organizationId)
                .orElse(null);
    }

    private DynamicModuleDescriptor permissionScopedDescriptor(String moduleAlias) {
        DynamicModuleDescriptor descriptor = recordService.describe(moduleAlias);
        return new DynamicModuleDescriptor(
                descriptor.moduleAlias(),
                descriptor.title(),
                descriptor.mainEntityAlias(),
                visibleModuleActions(moduleAlias, descriptor.actions()),
                descriptor.entities().stream()
                        .map(entity -> new DynamicEntityDescriptor(
                                entity.entityAlias(),
                                entity.title(),
                                entity.capabilities(),
                                entity.fields(),
                                entity.formulaRules(),
                                visibleEntityActions(moduleAlias, entity.entityAlias(), entity.actions()),
                                entity.views(),
                                entity.associationViews()
                        ))
                        .toList(),
                descriptor.relations(),
                descriptor.references(),
                descriptor.associationViews()
        );
    }

    private List<DynamicActionDescriptor> visibleModuleActions(String moduleAlias,
                                                               List<DynamicActionDescriptor> actions) {
        return actions.stream()
                .filter(action -> recordService.actionAuthorizationAvailability(
                        moduleAlias, action.code(), Set.of()).available())
                .toList();
    }

    private List<DynamicActionDescriptor> visibleEntityActions(String moduleAlias,
                                                               String entityAlias,
                                                               List<DynamicActionDescriptor> actions) {
        return actions.stream()
                .filter(action -> recordService.actionAuthorizationAvailability(
                        moduleAlias, entityAlias, action.code(), Set.of()).available())
                .toList();
    }

    private void requireSortInput(TreeSortWebRequest request) {
        if ((request.previousId() == null || request.previousId().isBlank())
                && (request.nextId() == null || request.nextId().isBlank())
                && (request.parentId() == null || request.parentId().isBlank())) {
            throw new IllegalArgumentException("tree sort requires previousId, nextId, or parentId");
        }
    }

    private <T> T tenantScope(String moduleAlias, Supplier<T> action) {
        dynamicModuleScopeService.requireTenantScope(moduleAlias);
        return action.get();
    }

    private Criteria criteria(String moduleAlias, String entityAlias, List<WebQueryCondition> conditions) {
        List<DynamicQueryCondition> queryConditions = DynamicWebQueryMapper.queryConditions(conditions);
        if (queryConditions.isEmpty()) {
            return Criteria.of();
        }
        return recordService.queryCriteria(moduleAlias, entityAlias, queryConditions);
    }

    private Criteria criteria(String moduleAlias, String entityAlias,
                              net.ximatai.muyun.spring.web.WebQueryCriteria criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return Criteria.of();
        }
        return DynamicWebQueryMapper.queryCriteria(criteria,
                conditions -> recordService.queryCriteria(moduleAlias, entityAlias, conditions));
    }

    private DynamicActionExecutionRequest actionRequest(String moduleAlias,
                                                        String entityAlias,
                                                        String pathRecordId,
                                                        DynamicWebActionRequest request) {
        DynamicWebActionRequest normalized = request == null ? DynamicWebActionRequest.empty() : request;
        String recordId = resolveActionRecordId(pathRecordId, normalized.recordId());
        DynamicActionExecutionRequest actionRequest = DynamicActionExecutionRequest.empty()
                .withRecordId(recordId)
                .withIds(normalized.ids())
                .withOrderedIds(normalized.orderedIds())
                .withBeforeId(normalized.beforeId())
                .withAfterId(normalized.afterId())
                .withParentId(normalized.parentId())
                .withFieldNames(normalized.fieldNames())
                .withQueryConditions(DynamicWebQueryMapper.queryConditions(normalized.conditions()))
                .withPayload(normalized.payload());
        if (normalized.record() != null && entityAlias != null) {
            actionRequest = actionRequest.withRecord(actionRecord(moduleAlias, entityAlias, recordId, normalized.record()));
        }
        if (!normalized.conditions().isEmpty() && entityAlias != null) {
            actionRequest = actionRequest.withCriteria(criteria(moduleAlias, entityAlias, normalized.conditions()));
        }
        if (normalized.page() != null) {
            actionRequest = actionRequest.withPageRequest(DynamicWebQueryMapper.page(normalized.page()));
        }
        if (!normalized.sorts().isEmpty()) {
            if (entityAlias != null) {
                DynamicWebQueryFieldSupport.validatePhysicalSorts(recordService.entity(moduleAlias, entityAlias),
                        normalized.sorts());
            }
            actionRequest = actionRequest.withSorts(List.of(DynamicWebQueryMapper.sorts(normalized.sorts())));
        }
        return actionRequest;
    }

    private void requireActionLevel(String moduleAlias,
                                    String actionCode,
                                    Set<EntityActionLevel> allowed,
        String messagePrefix) {
        DynamicActionDescriptor action = recordService.action(moduleAlias, actionCode);
        rejectReservedActionPath(action);
        EntityActionLevel level = action.actionLevel();
        if (!allowed.contains(level)) {
            throw new IllegalArgumentException(messagePrefix + actionCode);
        }
    }

    private void rejectReservedActionPath(DynamicActionDescriptor action) {
        if (PlatformWebPathRules.isReservedWebActionCode(action.code())
                && (action.category() != EntityActionCategory.STANDARD
                || !PlatformWebPathRules.isStandardActionPathCode(action.code()))) {
            String actionCode = action.code();
            throw new IllegalArgumentException("dynamic action path is reserved: " + actionCode);
        }
    }

    private String resolveActionRecordId(String pathRecordId, String bodyRecordId) {
        if (pathRecordId == null || pathRecordId.isBlank()) {
            return bodyRecordId;
        }
        if (bodyRecordId != null && !bodyRecordId.isBlank() && !pathRecordId.equals(bodyRecordId)) {
            throw new IllegalArgumentException("action path recordId must match request recordId");
        }
        return pathRecordId;
    }

    private DynamicRecord actionRecord(String moduleAlias,
                                       String entityAlias,
                                       String recordId,
                                       DynamicRecordPayload payload) {
        DynamicRecord record = record(moduleAlias, entityAlias, payload);
        if (recordId == null || recordId.isBlank()) {
            return record;
        }
        if (record.getId() != null && !record.getId().isBlank() && !recordId.equals(record.getId())) {
            throw new IllegalArgumentException("action request recordId must match record.id");
        }
        record.setId(recordId);
        return record;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

}
