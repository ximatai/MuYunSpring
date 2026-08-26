package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.role.RoleGrantableActionResolver;
import net.ximatai.muyun.spring.iam.role.RoleOwnerScopeType;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.platform.web.PageContextBindingDefinition;
import net.ximatai.muyun.spring.platform.web.PageContextTarget;
import net.ximatai.muyun.spring.platform.web.PageSelectionContextRequest;
import net.ximatai.muyun.spring.platform.web.ResolvedPageSelectionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleScopePageSelectionResolverTest {
    private final TenantService tenantService = mock(TenantService.class);
    private final OrganizationService organizationService = mock(OrganizationService.class);
    private final RoleScopePageSelectionResolver resolver = new RoleScopePageSelectionResolver(tenantService,
            organizationService);

    @AfterEach
    void clearActionContext() {
        ActionExecutionContextHolder.clear();
    }

    @Test
    void shouldResolvePlatformRoleScopeWithAnExplicitNullOwnerId() {
        CurrentUser user = CurrentUser.systemUser("admin", "Admin");

        ResolvedPageSelectionContext resolved = resolve(user, "platform", PlatformAction.QUERY);

        assertThat(resolved.values().get("ownerScopeType").value()).isEqualTo(RoleOwnerScopeType.PLATFORM);
        assertThat(resolved.values().get("ownerScopeId").present()).isTrue();
        assertThat(resolved.values().get("ownerScopeId").value()).isNull();
        assertThat(resolved.values().get("ownerScopeKey").value()).isEqualTo("platform");
        assertThat(resolved.values().get("tenantId").present()).isTrue();
        assertThat(resolved.values().get("tenantId").value()).isNull();
    }

    @Test
    void shouldResolveTenantAndOrganizationScopesOnlyInsideTheCurrentTenant() {
        CurrentUser user = CurrentUser.tenantUser("tenant-admin", "Tenant Admin", "tenant-a");
        Organization organization = new Organization();
        organization.setId("organization-a");
        organization.setTenantId("tenant-a");
        when(organizationService.requireEnabled("organization-a", "role owner organization is not active: organization-a"))
                .thenReturn(organization);

        ResolvedPageSelectionContext tenant = resolve(user, "tenant:tenant-a", PlatformAction.CREATE);
        ResolvedPageSelectionContext organizationScope = resolve(user, "organization:organization-a", PlatformAction.CREATE);

        assertThat(tenant.values().get("ownerScopeType").value()).isEqualTo(RoleOwnerScopeType.TENANT);
        assertThat(tenant.values().get("ownerScopeId").value()).isEqualTo("tenant-a");
        assertThat(organizationScope.values().get("ownerScopeType").value()).isEqualTo(RoleOwnerScopeType.ORGANIZATION);
        assertThat(organizationScope.values().get("ownerScopeId").value()).isEqualTo("organization-a");
        assertThat(organizationScope.values().get("ownerScopeKey").value()).isEqualTo("organization:organization-a");
        assertThat(organizationScope.values().get("tenantId").value()).isEqualTo("tenant-a");
        verify(tenantService, times(2)).requireActiveTenant("tenant-a");
    }

    @Test
    void shouldFailClosedForPlatformAndForeignTenantSelections() {
        CurrentUser user = CurrentUser.tenantUser("tenant-admin", "Tenant Admin", "tenant-a");

        assertThatThrownBy(() -> resolve(user, "platform", PlatformAction.QUERY))
                .isInstanceOf(PlatformAccessDeniedException.class);
        assertThatThrownBy(() -> resolve(user, "tenant:tenant-b", PlatformAction.QUERY))
                .isInstanceOf(PlatformAccessDeniedException.class)
                .hasMessageContaining("current tenant");
    }

    @Test
    void shouldRejectAResolverCallWithoutTheMatchingAuthorizedActionContext() {
        CurrentUser user = CurrentUser.systemUser("admin", "Admin");

        assertThatThrownBy(() -> resolver.resolve(request(user, "platform", PlatformAction.QUERY)))
                .isInstanceOf(PlatformAccessDeniedException.class)
                .hasMessageContaining("authorized action context");

        try (ActionExecutionContextHolder.Scope ignored = actionContext(user, PlatformAction.VIEW)) {
            assertThatThrownBy(() -> resolver.resolve(request(user, "platform", PlatformAction.QUERY)))
                    .isInstanceOf(PlatformAccessDeniedException.class)
                    .hasMessageContaining("does not match");
        }
    }

    @Test
    void shouldExposeAllRoleScopeBindingsFromTheController() {
        RoleWebController controller = new RoleWebController(mock(RoleGrantableActionResolver.class));
        controller.setRoleScopeSelectionResolver(resolver);

        List<PageContextBindingDefinition> bindings = controller.pageSelectionContextBindings();

        assertThat(bindings).extracting(PageContextBindingDefinition::sourceKey)
                .containsOnly(RoleScopePageSelectionResolver.SELECTION_KIND);
        assertThat(bindings).filteredOn(binding -> binding.target() == PageContextTarget.LIST_QUERY)
                .extracting(PageContextBindingDefinition::targetKey).containsExactly("ownerScopeKey");
        assertThat(bindings).filteredOn(binding -> binding.target() == PageContextTarget.FORM_DEFAULT)
                .extracting(PageContextBindingDefinition::targetKey)
                .containsExactly("ownerScopeType", "ownerScopeId", "ownerScopeKey");
        assertThat(bindings).filteredOn(binding -> binding.target() == PageContextTarget.MUTATION_CONSTRAINT)
                .extracting(PageContextBindingDefinition::targetKey)
                .containsExactly("ownerScopeType", "ownerScopeId", "ownerScopeKey", "tenantId");
        CurrentUser user = CurrentUser.systemUser("admin", "Admin");
        try (ActionExecutionContextHolder.Scope ignored = actionContext(user, PlatformAction.QUERY)) {
            assertThat(controller.pageSelectionContextResolvers()
                    .resolve(request(user, "platform", PlatformAction.QUERY))
                    .selectionKey()).isEqualTo("platform");
        }
    }

    private ResolvedPageSelectionContext resolve(CurrentUser user, String key, PlatformAction action) {
        try (ActionExecutionContextHolder.Scope ignored = actionContext(user, action)) {
            return resolver.resolve(request(user, key, action));
        }
    }

    private PageSelectionContextRequest request(CurrentUser user, String key, PlatformAction action) {
        return new PageSelectionContextRequest(RoleService.MODULE_ALIAS, RoleScopePageSelectionResolver.SELECTION_KIND,
                key, action, user, null);
    }

    private ActionExecutionContextHolder.Scope actionContext(CurrentUser user, PlatformAction action) {
        ActionExecutionContext context = ActionExecutionContext.ofPlatformAction(RoleService.MODULE_ALIAS, action,
                java.util.Set.of(), Optional.of(user));
        return ActionExecutionContextHolder.use(context.withAuthorizationResult(ActionAuthorizationResult.allowed(context)));
    }
}
