package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.platform.web.DynamicRuntimeRead;

import jakarta.servlet.http.HttpServletResponse;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.query.QueryLikePattern;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.exchange.exporter.DynamicExportCommand;
import net.ximatai.muyun.spring.platform.exchange.exporter.DynamicExportFacade;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.metadata.ResolvedModuleMetadataField;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshot;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshotService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItemService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplate;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigField;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSet;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

@DynamicRuntimeRead
@RestController
@RequestMapping("/{moduleAlias:[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+}/export")
public class DynamicExportWebController {
    private final DynamicRecordService recordService;
    private final ActiveTenantVerifier activeTenantVerifier;
    private final DynamicExportFacade exportFacade;
    private final PlatformPageConfigSnapshotService pageConfigSnapshotService;
    private final PlatformQueryItemService queryItemService;
    private final ModuleMetadataFieldService moduleMetadataFieldService;
    private final FieldUiControlService fieldUiControlService;
    private final FieldUiControlBindingService fieldUiControlBindingService;

    public DynamicExportWebController(DynamicRecordService recordService,
                                      ActiveTenantVerifier activeTenantVerifier,
                                      DynamicExportFacade exportFacade) {
        this(recordService, activeTenantVerifier, exportFacade, (PlatformPageConfigSnapshotService) null,
                null, null, null, null);
    }

    @Autowired
    public DynamicExportWebController(DynamicRecordService recordService,
                                      ActiveTenantVerifier activeTenantVerifier,
                                      DynamicExportFacade exportFacade,
                                      ObjectProvider<PlatformPageConfigSnapshotService> pageConfigSnapshotServiceProvider,
                                      ObjectProvider<PlatformQueryItemService> queryItemServiceProvider,
                                      ObjectProvider<ModuleMetadataFieldService> moduleMetadataFieldServiceProvider,
                                      ObjectProvider<FieldUiControlService> fieldUiControlServiceProvider,
                                      ObjectProvider<FieldUiControlBindingService> fieldUiControlBindingServiceProvider) {
        this(recordService, activeTenantVerifier, exportFacade,
                pageConfigSnapshotServiceProvider == null ? null : pageConfigSnapshotServiceProvider.getIfAvailable(),
                queryItemServiceProvider == null ? null : queryItemServiceProvider.getIfAvailable(),
                moduleMetadataFieldServiceProvider == null ? null : moduleMetadataFieldServiceProvider.getIfAvailable(),
                fieldUiControlServiceProvider == null ? null : fieldUiControlServiceProvider.getIfAvailable(),
                fieldUiControlBindingServiceProvider == null ? null : fieldUiControlBindingServiceProvider.getIfAvailable());
    }

    public DynamicExportWebController(DynamicRecordService recordService,
                                      ActiveTenantVerifier activeTenantVerifier,
                                      DynamicExportFacade exportFacade,
                                      PlatformPageConfigSnapshotService pageConfigSnapshotService,
                                      PlatformQueryItemService queryItemService) {
        this(recordService, activeTenantVerifier, exportFacade, pageConfigSnapshotService, queryItemService, null, null, null);
    }

    public DynamicExportWebController(DynamicRecordService recordService,
                                      ActiveTenantVerifier activeTenantVerifier,
                                      DynamicExportFacade exportFacade,
                                      PlatformPageConfigSnapshotService pageConfigSnapshotService,
                                      PlatformQueryItemService queryItemService,
                                      ModuleMetadataFieldService moduleMetadataFieldService) {
        this(recordService, activeTenantVerifier, exportFacade, pageConfigSnapshotService, queryItemService,
                moduleMetadataFieldService, null, null);
    }

    public DynamicExportWebController(DynamicRecordService recordService,
                                      ActiveTenantVerifier activeTenantVerifier,
                                      DynamicExportFacade exportFacade,
                                      PlatformPageConfigSnapshotService pageConfigSnapshotService,
                                      PlatformQueryItemService queryItemService,
                                      ModuleMetadataFieldService moduleMetadataFieldService,
                                      FieldUiControlService fieldUiControlService,
                                      FieldUiControlBindingService fieldUiControlBindingService) {
        this.recordService = recordService;
        this.activeTenantVerifier = activeTenantVerifier;
        this.exportFacade = exportFacade;
        this.pageConfigSnapshotService = pageConfigSnapshotService;
        this.queryItemService = queryItemService;
        this.moduleMetadataFieldService = moduleMetadataFieldService;
        this.fieldUiControlService = fieldUiControlService;
        this.fieldUiControlBindingService = fieldUiControlBindingService;
    }

    @PostMapping("/data")
    @ActionEndpoint(PlatformAction.EXPORT)
    public void exportData(@PathVariable String moduleAlias,
                           @RequestBody(required = false) WebQueryRequest request,
                           HttpServletResponse response) {
        tenantScope(moduleAlias, () -> {
            DynamicModuleDescriptor descriptor = recordService.describe(moduleAlias);
            requireExchangeCapability(descriptor);
            byte[] bytes = exportFacade.exportWorkbook(exportCommand(moduleAlias, descriptor, request));
            writeXlsx(response, moduleAlias.replace('.', '_') + "-export.xlsx", bytes);
            return null;
        });
    }

    @PostMapping("/selected")
    @ActionEndpoint(PlatformAction.EXPORT)
    public void exportSelected(@PathVariable String moduleAlias,
                               @RequestBody(required = false) DynamicSelectedExportRequest request,
                               HttpServletResponse response) {
        tenantScope(moduleAlias, () -> {
            DynamicModuleDescriptor descriptor = recordService.describe(moduleAlias);
            requireExchangeCapability(descriptor);
            byte[] bytes = exportFacade.exportWorkbook(selectedExportCommand(moduleAlias, descriptor, request));
            writeXlsx(response, moduleAlias.replace('.', '_') + "-selected-export.xlsx", bytes);
            return null;
        });
    }

    private DynamicExportCommand exportCommand(String moduleAlias,
                                               DynamicModuleDescriptor descriptor,
                                               WebQueryRequest request) {
        WebQueryRequest normalized = request == null ? new WebQueryRequest(null, List.of(), List.of()) : request;
        DynamicEntityOperations operations = recordService.mainEntity(moduleAlias);
        Criteria criteria = queryCriteria(moduleAlias, operations, normalized);
        PageRequest pageRequest = DynamicWebQueryMapper.page(normalized.pageOrDefault());
        DynamicWebQueryFieldSupport.validatePhysicalSorts(operations, normalized.sorts());
        Sort[] sorts = DynamicWebQueryMapper.sorts(normalized.sorts());
        return new DynamicExportCommand(descriptor, criteria, pageRequest, List.of(sorts));
    }

    private DynamicExportCommand selectedExportCommand(String moduleAlias,
                                                       DynamicModuleDescriptor descriptor,
                                                       DynamicSelectedExportRequest request) {
        DynamicSelectedExportRequest normalized = request == null
                ? new DynamicSelectedExportRequest(List.of(), null)
                : request;
        WebQueryRequest query = normalized.query();
        List<String> selectedIds = selectedIds(normalized.ids());
        DynamicEntityOperations operations = recordService.mainEntity(moduleAlias);
        Criteria queryCriteria = queryCriteria(moduleAlias, operations, query);
        Criteria selectedCriteria = Criteria.of().in(StandardEntitySchema.ID_FIELD, selectedIds);
        Criteria criteria = andCriteria(queryCriteria, selectedCriteria);
        WebQueryRequest queryOrDefault = query == null ? new WebQueryRequest(null, List.of(), List.of()) : query;
        PageRequest pageRequest = DynamicWebQueryMapper.page(queryOrDefault.pageOrDefault());
        DynamicWebQueryFieldSupport.validatePhysicalSorts(operations, queryOrDefault.sorts());
        Sort[] sorts = DynamicWebQueryMapper.sorts(queryOrDefault.sorts());
        return new DynamicExportCommand(descriptor, criteria, pageRequest, List.of(sorts));
    }

    private Criteria queryCriteria(String moduleAlias,
                                   DynamicEntityOperations operations,
                                   WebQueryRequest request) {
        Criteria templateCriteria = Criteria.of();
        if (request != null && hasText(request.queryTemplateId())) {
            requireLowCodeQueryServices();
            validateQueryTemplateBelongsToModule(moduleAlias, request.queryTemplateId());
            templateCriteria = queryItemService.compile(request.queryTemplateId(), request.externalQueryValues());
        }
        Criteria manualCriteria = request == null || request.conditions().isEmpty()
                ? Criteria.of()
                : operations.queryCriteria(DynamicWebQueryMapper.queryConditions(request.conditions()));
        Criteria treeCriteria = request == null || request.criteria() == null
                ? Criteria.of()
                : DynamicWebQueryMapper.queryCriteria(request.criteria(), operations::queryCriteria);
        Criteria queryFormCriteria = DynamicWebQueryFormSupport.queryFormCriteria(moduleAlias,
                request, pageConfigSnapshotService, moduleMetadataFieldService, fieldUiControlService,
                fieldUiControlBindingService, operations::queryCriteria);
        Criteria quickCriteria = quickSearchCriteria(moduleAlias, request);
        return andCriteria(templateCriteria, queryFormCriteria, manualCriteria, treeCriteria, quickCriteria);
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
                group.orLikeIgnoreCase(field, QueryLikePattern.containsLiteral(keyword));
            }
        });
        return criteria;
    }

    private List<String> quickSearchFields(String moduleAlias, WebQueryRequest request) {
        requireLowCodeQuickSearchServices();
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

    private void validateQueryTemplateBelongsToModule(String moduleAlias, String queryTemplateId) {
        PlatformPageConfigSnapshot snapshot = pageConfigSnapshotService.snapshot(moduleAlias);
        PlatformQueryTemplate template = snapshot.queryTemplates().stream()
                .filter(item -> Objects.equals(item.getId(), queryTemplateId))
                .findFirst()
                .orElseThrow(() -> new PlatformException("Query template is not enabled in module snapshot: "
                        + queryTemplateId));
        if (!Objects.equals(template.getModuleAlias(), moduleAlias)) {
            throw new PlatformException("Query template must belong to module: " + moduleAlias);
        }
    }

    private void requireLowCodeQueryServices() {
        if (pageConfigSnapshotService == null || queryItemService == null) {
            throw new PlatformException("dynamic low-code query services are not configured");
        }
    }

    private void requireLowCodeQuickSearchServices() {
        if (pageConfigSnapshotService == null || moduleMetadataFieldService == null) {
            throw new PlatformException("dynamic low-code quick search services are not configured");
        }
    }

    private PlatformUiConfig publishedUiConfig(PlatformPageConfigSnapshot snapshot, String uiConfigId) {
        return snapshot.uiConfigs().stream()
                .filter(config -> Objects.equals(config.getId(), uiConfigId))
                .findFirst()
                .orElseThrow(() -> new PlatformException("UI config is not published in module snapshot: "
                        + uiConfigId));
    }

    private void requireListUiConfig(PlatformPageConfigSnapshot snapshot, PlatformUiConfig uiConfig) {
        PlatformUiSet uiSet = snapshot.uiSets().stream()
                .filter(set -> Objects.equals(set.getId(), uiConfig.getUiSetId()))
                .findFirst()
                .orElseThrow(() -> new PlatformException("UI config set is not published in module snapshot: "
                        + uiConfig.getUiSetId()));
        if (uiSet.getSetType() != PlatformUiSetType.LIST) {
            throw new PlatformException("Quick search requires LIST UI config: " + uiConfig.getId());
        }
    }

    private void requireExchangeCapability(DynamicModuleDescriptor descriptor) {
        DynamicEntityDescriptor mainEntity = descriptor.entities().stream()
                .filter(entity -> entity.entityAlias().equals(descriptor.mainEntityAlias()))
                .findFirst()
                .orElseThrow(() -> new PlatformException("dynamic module main entity not found: "
                        + descriptor.mainEntityAlias()));
        if (!mainEntity.capabilities().contains(EntityCapability.EXCHANGE.name())) {
            throw new PlatformException("dynamic entity does not support capability: EXCHANGE");
        }
    }

    private void writeXlsx(HttpServletResponse response, String fileName, byte[] bytes) {
        try {
            response.setContentType(DynamicImportWebController.XLSX_CONTENT_TYPE);
            response.setHeader("Content-Disposition", DynamicImportWebController.contentDisposition(fileName));
            response.setHeader("Access-Control-Expose-Headers", "Content-Disposition,X-Export-FileName");
            response.setHeader("X-Export-FileName", fileName);
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
        } catch (IOException ex) {
            throw new PlatformException("dynamic export workbook write failed", ex);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> selectedIds(List<String> ids) {
        if (ids == null) {
            throw new PlatformException("dynamic selected export ids must not be empty");
        }
        List<String> selected = ids.stream()
                .filter(this::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (selected.isEmpty()) {
            throw new PlatformException("dynamic selected export ids must not be empty");
        }
        return selected;
    }

    private <T> T tenantScope(String moduleAlias, Supplier<T> action) {
        String tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new PlatformException(moduleAlias + " requires tenant context"));
        activeTenantVerifier.verifyActiveTenant(tenantId);
        return action.get();
    }

}

record DynamicSelectedExportRequest(List<String> ids, WebQueryRequest query) {
}
