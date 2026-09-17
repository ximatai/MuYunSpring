package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.web.TenantRequestScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModuleTenantScopeTest {
    @Test
    void shouldUseTheCompiledStaticModuleTenantContract() {
        TenantRequestScope requestScope = mock(TenantRequestScope.class);
        PlatformModuleService modules = mock(PlatformModuleService.class);
        ModuleTenantScope scope = new ModuleTenantScope(new StaticModuleDefinitionCatalog(List.of(
                StaticModuleDefinition.builder("iam", "iam.employee", "职员管理").tenantRequired(true).build(),
                StaticModuleDefinition.builder("iam", "iam.user", "用户管理").tenantRequired(false).build()
        )), modules, requestScope);

        scope.requireActiveTenantIfRequired("iam.employee");
        scope.requireActiveTenantIfRequired("iam.user");

        verify(requestScope).requireActiveTenant("iam.employee");
        verify(requestScope, never()).requireActiveTenant("iam.user");
        verify(modules, never()).resolveVisibleModule("iam.employee");
        assertThat(scope.tenantRequired("iam.employee")).isTrue();
        assertThat(scope.tenantRequired("iam.user")).isFalse();
    }

    @Test
    void shouldRequireTenantOnlyForCatalogConfirmedDynamicModules() {
        TenantRequestScope requestScope = mock(TenantRequestScope.class);
        PlatformModuleService modules = mock(PlatformModuleService.class);
        PlatformModule dynamic = new PlatformModule();
        dynamic.setAlias("education.exam");
        dynamic.setModuleKind(ModuleKind.DYNAMIC);
        when(modules.resolveVisibleModule("education.exam")).thenReturn(dynamic);
        ModuleTenantScope scope = new ModuleTenantScope(new StaticModuleDefinitionCatalog(List.of()), modules, requestScope);

        scope.requireActiveTenantIfRequired("education.exam");
        scope.requireActiveTenantIfRequired("education.unknown");

        verify(requestScope).requireActiveTenant("education.exam");
        verify(requestScope, never()).requireActiveTenant("education.unknown");
        assertThat(scope.tenantRequired("education.exam")).isTrue();
        assertThat(scope.tenantRequired("education.unknown")).isFalse();
    }
}
