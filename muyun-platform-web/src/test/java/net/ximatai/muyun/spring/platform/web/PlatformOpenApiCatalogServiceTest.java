package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicModuleRegistry;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.web.TenantRequestScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformOpenApiCatalogServiceTest {

    @Test
    void shouldRequireAnActualDocumentSourceForStaticAndDynamicModules() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModule staticReady = module("crm.customer", "客户", ModuleKind.STATIC);
        PlatformModule staticMissing = module("crm.contract", "合同", ModuleKind.STATIC);
        PlatformModule dynamicReady = module("crm.order", "订单", ModuleKind.DYNAMIC);
        PlatformModule dynamicMissing = module("crm.invoice", "发票", ModuleKind.DYNAMIC);
        when(moduleService.listVisibleModules("tenant-a"))
                .thenReturn(List.of(staticReady, staticMissing, dynamicReady, dynamicMissing));

        StaticModuleDefinitionCatalog staticCatalog = new StaticModuleDefinitionCatalog(List.of(
                StaticModuleDefinition.builder("crm", "crm.customer", "客户")
                        .openApiAvailable(true)
                        .build(),
                StaticModuleDefinition.builder("crm", "crm.contract", "合同")
                        .openApiAvailable(false)
                        .build()
        ));
        DynamicRecordRuntime runtime = mock(DynamicRecordRuntime.class);
        DynamicModuleRegistry registry = mock(DynamicModuleRegistry.class);
        when(runtime.registry()).thenReturn(registry);
        when(registry.containsModule("crm.order")).thenReturn(true);
        TenantRequestScope dynamicScope = mock(TenantRequestScope.class);
        when(dynamicScope.hasActiveTenant("tenant-a")).thenReturn(true);

        PlatformOpenApiCatalogService service = new PlatformOpenApiCatalogService(
                moduleService,
                staticCatalog,
                runtime,
                new ActionEndpointContextResolver(),
                new AllowAllActionExecutionPolicyService(),
                dynamicScope
        );

        assertThat(service.discover("tenant-a"))
                .containsExactly(
                        new OpenApiModuleCatalogItem("crm.customer", "客户", "static",
                                "/crm.customer/openapi"),
                        new OpenApiModuleCatalogItem("crm.order", "订单", "dynamic",
                                "/crm.order/openapi")
                );
        verify(registry).containsModule("crm.order");
        verify(registry).containsModule("crm.invoice");
        verify(moduleService).listVisibleModules("tenant-a");
    }

    @Test
    void shouldHideDocumentsThatTheCallerCannotDescribeOrEnter() {
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        PlatformModule allowed = module("crm.customer", "客户", ModuleKind.STATIC);
        PlatformModule denied = module("crm.contract", "合同", ModuleKind.STATIC);
        PlatformModule dynamic = module("crm.order", "订单", ModuleKind.DYNAMIC);
        when(moduleService.listVisibleModules((String) null)).thenReturn(List.of(allowed, denied, dynamic));

        StaticModuleDefinitionCatalog staticCatalog = new StaticModuleDefinitionCatalog(List.of(
                StaticModuleDefinition.builder("crm", "crm.customer", "客户")
                        .openApiAvailable(true)
                        .build(),
                StaticModuleDefinition.builder("crm", "crm.contract", "合同")
                        .openApiAvailable(true)
                        .build()
        ));
        DynamicRecordRuntime runtime = mock(DynamicRecordRuntime.class);
        DynamicModuleRegistry registry = mock(DynamicModuleRegistry.class);
        when(runtime.registry()).thenReturn(registry);
        when(registry.containsModule("crm.order")).thenReturn(true);
        ActionExecutionPolicyService authorization = mock(ActionExecutionPolicyService.class);
        doThrow(new PlatformAccessDeniedException("action permission denied"))
                .when(authorization)
                .authorize(argThat(context -> context != null
                        && "crm.contract".equals(context.moduleAlias())));
        TenantRequestScope dynamicScope = mock(TenantRequestScope.class);
        when(dynamicScope.hasActiveTenant("tenant-a")).thenReturn(false);

        PlatformOpenApiCatalogService service = new PlatformOpenApiCatalogService(
                moduleService,
                staticCatalog,
                runtime,
                new ActionEndpointContextResolver(),
                authorization,
                dynamicScope
        );

        assertThat(service.discover("tenant-a"))
                .extracting(OpenApiModuleCatalogItem::moduleAlias)
                .containsExactly("crm.customer");
        verify(moduleService).listVisibleModules((String) null);
    }

    private PlatformModule module(String alias, String title, ModuleKind kind) {
        PlatformModule module = new PlatformModule();
        module.setAlias(alias);
        module.setApplicationAlias(alias.substring(0, alias.indexOf('.')));
        module.setTitle(title);
        module.setModuleKind(kind);
        return module;
    }
}
