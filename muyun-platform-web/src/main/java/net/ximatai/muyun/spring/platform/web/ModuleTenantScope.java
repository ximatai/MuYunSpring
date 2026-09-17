package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.web.TenantRequestScope;

/**
 * Applies the resolved module tenant contract to business delivery endpoints.
 *
 * <p>Static modules declare the contract through their compiled definition. Dynamic record
 * modules are tenant business modules by definition, but only after the platform module catalog
 * confirms that the alias is dynamic. Unknown aliases deliberately retain their normal endpoint
 * publication or resource-not-found handling instead of being guessed as dynamic.</p>
 */
public class ModuleTenantScope {
    private final StaticModuleDefinitionCatalog staticModules;
    private final PlatformModuleService modules;
    private final TenantRequestScope requestScope;

    public ModuleTenantScope(StaticModuleDefinitionCatalog staticModules,
                             PlatformModuleService modules,
                             TenantRequestScope requestScope) {
        this.staticModules = staticModules;
        this.modules = modules;
        this.requestScope = requestScope;
    }

    /** Requires an active request tenant when the resolved module is a tenant business module. */
    public void requireActiveTenantIfRequired(String moduleAlias) {
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        if (tenantRequired(validAlias)) {
            requestScope.requireActiveTenant(validAlias);
        }
    }

    public boolean tenantRequired(String moduleAlias) {
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        return staticModules.find(validAlias)
                .map(StaticModuleDefinition::tenantRequired)
                .orElseGet(() -> dynamicModule(validAlias));
    }

    private boolean dynamicModule(String moduleAlias) {
        if (modules == null) {
            return false;
        }
        PlatformModule module = modules.resolveVisibleModule(moduleAlias);
        return module != null && module.getModuleKind() == ModuleKind.DYNAMIC;
    }
}
