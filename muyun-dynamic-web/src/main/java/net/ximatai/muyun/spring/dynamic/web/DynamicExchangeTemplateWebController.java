package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.platform.web.DynamicRuntimeRead;

import jakarta.servlet.http.HttpServletResponse;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.option.OptionSourceRegistry;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.exchange.model.ExcelWorkbookPlan;
import net.ximatai.muyun.spring.platform.exchange.template.DynamicExchangeTemplateOptions;
import net.ximatai.muyun.spring.platform.exchange.template.DynamicExchangeTemplatePlanBuilder;
import net.ximatai.muyun.spring.platform.exchange.template.DynamicRecordReferenceDropdownResolver;
import net.ximatai.muyun.spring.platform.exchange.writer.ExcelWorkbookPlanWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.function.Supplier;

@DynamicRuntimeRead
@RestController
@RequestMapping("/{moduleAlias:[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+}/exchange")
public class DynamicExchangeTemplateWebController {
    private final DynamicRecordService recordService;
    private final ActiveTenantVerifier activeTenantVerifier;
    private final DynamicExchangeTemplatePlanBuilder templatePlanBuilder;
    private final ExcelWorkbookPlanWriter workbookWriter;

    @Autowired
    public DynamicExchangeTemplateWebController(DynamicRecordService recordService,
                                                ActiveTenantVerifier activeTenantVerifier,
                                                OptionSourceRegistry optionSourceRegistry) {
        this(recordService, activeTenantVerifier,
                new DynamicExchangeTemplatePlanBuilder(optionSourceRegistry,
                        new DynamicRecordReferenceDropdownResolver(recordService)),
                new ExcelWorkbookPlanWriter());
    }

    DynamicExchangeTemplateWebController(DynamicRecordService recordService,
                                         ActiveTenantVerifier activeTenantVerifier,
                                         DynamicExchangeTemplatePlanBuilder templatePlanBuilder,
                                         ExcelWorkbookPlanWriter workbookWriter) {
        this.recordService = recordService;
        this.activeTenantVerifier = activeTenantVerifier;
        this.templatePlanBuilder = templatePlanBuilder;
        this.workbookWriter = workbookWriter;
    }

    @PostMapping("/template")
    @ActionEndpoint(PlatformAction.IMPORT)
    public void template(@PathVariable String moduleAlias,
                         @RequestBody(required = false) DynamicExchangeTemplateRequest request,
                         HttpServletResponse response) {
        tenantScope(moduleAlias, () -> {
            writeTemplate(moduleAlias, request, response);
            return null;
        });
    }

    private void writeTemplate(String moduleAlias,
                               DynamicExchangeTemplateRequest request,
                               HttpServletResponse response) {
        DynamicModuleDescriptor descriptor = recordService.describe(moduleAlias);
        requireExchangeCapability(descriptor);
        ExcelWorkbookPlan plan = templatePlanBuilder.build(descriptor, templateOptions(request));
        byte[] bytes = workbookWriter.writeToBytes(plan);
        writeXlsx(response, moduleAlias.replace('.', '_') + "-exchange-template.xlsx", bytes);
    }

    private DynamicExchangeTemplateOptions templateOptions(DynamicExchangeTemplateRequest request) {
        if (request == null) {
            return DynamicExchangeTemplateOptions.DEFAULT;
        }
        return DynamicExchangeTemplateOptions.of(
                request.disabledReferenceDropdownFields(),
                request.referenceDropdownLimit()
        );
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
            response.setHeader("Access-Control-Expose-Headers", "Content-Disposition,X-Exchange-FileName");
            response.setHeader("X-Exchange-FileName", fileName);
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
        } catch (IOException ex) {
            throw new PlatformException("dynamic exchange template write failed", ex);
        }
    }

    private <T> T tenantScope(String moduleAlias, Supplier<T> action) {
        String tenantId = TenantContext.currentTenantId()
                .orElseThrow(() -> new PlatformException(moduleAlias + " requires tenant context"));
        activeTenantVerifier.verifyActiveTenant(tenantId);
        return action.get();
    }

}
