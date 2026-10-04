package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.platform.web.ModuleExecutionPlanCatalog;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.ErrorScope;
import net.ximatai.muyun.spring.platform.web.DynamicRuntimeRead;

import jakarta.servlet.http.HttpServletResponse;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

@DynamicRuntimeRead
@RestController
@RequestMapping("/{moduleAlias:[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+}/export")
public class DynamicExportWebController {
    private final DynamicRecordService recordService;
    private final ActiveTenantVerifier activeTenantVerifier;
    private final DynamicExportFacade exportFacade;
    private final ModuleExecutionPlanCatalog executionPlanCatalog;
    private final DynamicModuleQuerySupport querySupport = new DynamicModuleQuerySupport();

    public DynamicExportWebController(DynamicRecordService recordService,
                                      ActiveTenantVerifier activeTenantVerifier,
                                      DynamicExportFacade exportFacade,
                                      ModuleExecutionPlanCatalog executionPlanCatalog) {
        this.recordService = recordService;
        this.activeTenantVerifier = activeTenantVerifier;
        this.exportFacade = exportFacade;
        this.executionPlanCatalog = executionPlanCatalog;
    }

    @PostMapping("/data")
    @ActionEndpoint(PlatformAction.EXPORT)
    public void exportData(@PathVariable String moduleAlias,
                           @RequestBody(required = false) WebQueryRequest request,
                           HttpServletResponse response) {
        tenantScope(moduleAlias, () -> {
            DynamicModuleDescriptor descriptor = recordService.describe(moduleAlias);
            requireExchangeCapability(descriptor);
            byte[] bytes = exportFacade.exportWorkbook(exportCommand(descriptor, request));
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
            byte[] bytes = exportFacade.exportWorkbook(selectedExportCommand(descriptor, request));
            writeXlsx(response, moduleAlias.replace('.', '_') + "-selected-export.xlsx", bytes);
            return null;
        });
    }

    private DynamicExportCommand exportCommand(DynamicModuleDescriptor descriptor, WebQueryRequest request) {
        String moduleAlias = descriptor.moduleAlias();
        WebQueryRequest normalized = request == null ? new WebQueryRequest(null, List.of(), List.of()) : request;
        DynamicEntityOperations operations = recordService.mainEntity(moduleAlias);
        var plan = executionPlanCatalog.find(moduleAlias).orElseThrow(() -> PlatformErrors.config(
                PlatformErrorCodes.CONFIG_MISSING,
                "Dynamic module has no executable published page plan: " + moduleAlias,
                ErrorScope.module(moduleAlias)));
        querySupport.validateSorts(plan.querySchema(), normalized.sorts(), querySupport.runtimeSortFields(descriptor));
        Criteria criteria = querySupport.criteria(plan, normalized, operations::queryCriteria);
        DynamicWebQueryFieldSupport.validatePhysicalSorts(operations, normalized.sorts());
        return new DynamicExportCommand(descriptor, criteria, exportPage(normalized),
                List.of(DynamicWebQueryMapper.sorts(normalized.sorts())));
    }

    private DynamicExportCommand selectedExportCommand(DynamicModuleDescriptor descriptor,
                                                       DynamicSelectedExportRequest request) {
        DynamicSelectedExportRequest normalized = request == null
                ? new DynamicSelectedExportRequest(List.of(), null) : request;
        List<String> ids = selectedIds(normalized.ids());
        DynamicExportCommand query = exportCommand(descriptor, normalized.query());
        Criteria criteria = Criteria.copyOf(query.criteria()).and(Criteria.of().in(StandardEntitySchema.ID_FIELD, ids));
        return new DynamicExportCommand(descriptor, criteria, query.pageRequest(), query.sorts());
    }

    private PageRequest exportPage(WebQueryRequest request) {
        return request.unpagedEnabled() ? new PageRequest(0, Integer.MAX_VALUE)
                : DynamicWebQueryMapper.page(request.pageOrDefault());
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
