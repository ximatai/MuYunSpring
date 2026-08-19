package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.web.TenantRequestScope;
import org.springframework.stereotype.Service;

import java.util.List;

/** Builds the OpenAPI document catalog visible and reachable from the current Web request. */
@Service
public class PlatformOpenApiCatalogService {
    private final PlatformModuleService moduleService;
    private final StaticModuleDefinitionCatalog staticModuleCatalog;
    private final DynamicRecordRuntime dynamicRecordRuntime;
    private final ActionEndpointContextResolver actionContextResolver;
    private final ActionExecutionPolicyService actionExecutionPolicyService;
    private final TenantRequestScope tenantRequestScope;

    public PlatformOpenApiCatalogService(PlatformModuleService moduleService,
                                         StaticModuleDefinitionCatalog staticModuleCatalog,
                                         DynamicRecordRuntime dynamicRecordRuntime,
                                         ActionEndpointContextResolver actionContextResolver,
                                         ActionExecutionPolicyService actionExecutionPolicyService,
                                         TenantRequestScope tenantRequestScope) {
        this.moduleService = moduleService;
        this.staticModuleCatalog = staticModuleCatalog;
        this.dynamicRecordRuntime = dynamicRecordRuntime;
        this.actionContextResolver = actionContextResolver;
        this.actionExecutionPolicyService = actionExecutionPolicyService;
        this.tenantRequestScope = tenantRequestScope;
    }

    /**
     * Discovers documents without weakening their endpoint authority. The request tenant is
     * captured by the Controller before its system-scoped module-catalog read begins.
     */
    public List<OpenApiModuleCatalogItem> discover(String requestTenantId) {
        boolean activeTenant = tenantRequestScope.hasActiveTenant(requestTenantId);
        return moduleService.listVisibleModules(activeTenant ? requestTenantId : null).stream()
                .filter(this::documentExists)
                .filter(this::describable)
                .filter(module -> contextAvailable(module, activeTenant))
                .map(this::toCatalogItem)
                .toList();
    }

    private boolean documentExists(PlatformModule module) {
        if (module.getModuleKind() == ModuleKind.DYNAMIC) {
            return dynamicRecordRuntime.registry().containsModule(module.getAlias());
        }
        return staticModuleCatalog.find(module.getAlias())
                .map(StaticModuleDefinition::openApiAvailable)
                .orElse(false);
    }

    private boolean describable(PlatformModule module) {
        try {
            actionExecutionPolicyService.authorize(actionContextResolver.resolveModuleAction(
                    module.getAlias(), PlatformAction.VIEW));
            return true;
        } catch (AuthenticationRequiredException | PlatformAccessDeniedException ignored) {
            return false;
        }
    }

    private boolean contextAvailable(PlatformModule module, boolean activeTenant) {
        return module.getModuleKind() != ModuleKind.DYNAMIC
                || activeTenant;
    }

    private OpenApiModuleCatalogItem toCatalogItem(PlatformModule module) {
        return new OpenApiModuleCatalogItem(module.getAlias(), module.getTitle(),
                module.getModuleKind().getCode(), "/" + module.getAlias() + "/openapi");
    }
}
