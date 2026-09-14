package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.web.WebPageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class RoleScopeSelectionFacadeTest {
    private final TenantService tenantService = mock(TenantService.class);
    private final OrganizationService organizationService = mock(OrganizationService.class);
    private final RoleScopeSelectionFacade facade = new RoleScopeSelectionFacade(tenantService, organizationService);

    @AfterEach
    void clearContexts() {
        CurrentUserContext.clear();
        ActionExecutionContextHolder.clear();
    }

    @Test
    void shouldDescribeOnlyTheSystemTenantNavigatorAndPlatformDirectSelection() {
        RoleScopeSelectionFacade.RoleScopeSelectionDescriptor descriptor = inAuthorizedQuery(
                CurrentUser.systemUser("admin", "Admin"), facade::descriptor);

        assertThat(descriptor.directSelections())
                .containsExactly(new RoleScopeSelectionFacade.DirectSelection("platform", "平台角色"));
        assertThat(descriptor.navigations()).containsExactly(new RoleScopeSelectionFacade.Navigation(
                RoleScopeSelectionFacade.TENANT_LIST_NAVIGATION_KEY,
                RoleScopeSelectionFacade.ScopeLevel.TENANT, "租户"));
    }

    @Test
    void shouldPageActiveTenantCandidatesOnlyThroughTheDescriptorNavigationKey() {
        Tenant tenant = new Tenant();
        tenant.setId("tenant-a");
        tenant.setTitle("演示租户");
        @SuppressWarnings("unchecked")
        PageResult<Tenant> page = mock(PageResult.class);
        when(page.getRecords()).thenReturn(List.of(tenant));
        when(page.getTotal()).thenReturn(1L);
        when(page.getPageNum()).thenReturn(1);
        when(page.getPageSize()).thenReturn(20);
        when(page.getPages()).thenReturn(1L);
        when(page.isTotalKnown()).thenReturn(true);
        when(tenantService.enabledCriteria(any(Criteria.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tenantService.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class))).thenReturn(page);

        var response = inAuthorizedQuery(CurrentUser.systemUser("admin", "Admin"), () -> facade.candidates(
                new RoleScopeSelectionFacade.CandidateRequest(RoleScopeSelectionFacade.TENANT_LIST_NAVIGATION_KEY,
                        "tenant", "演示", null, new WebPageRequest(1, 20))));

        assertThat(response.records()).containsExactly(new RoleScopeSelectionFacade.Candidate("tenant-a", "演示租户",
                "tenant-a", "tenant:tenant-a", false));
        verify(tenantService).pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class));
    }

    @Test
    void shouldRejectTenantCandidatesForAKeyThatWasNotIssuedByTheDescriptor() {
        assertThatThrownBy(() -> inAuthorizedQuery(CurrentUser.systemUser("admin", "Admin"), () -> facade.candidates(
                new RoleScopeSelectionFacade.CandidateRequest("tenant:tenant-a", "tenant", null, null, null))))
                .isInstanceOf(PlatformAccessDeniedException.class)
                .hasMessageContaining("tenant navigation");
        verifyNoInteractions(tenantService, organizationService);
    }

    @Test
    void shouldRejectAForeignTenantOrganizationTreeForATenantIdentity() {
        CurrentUser tenantUser = CurrentUser.tenantUser("tenant-admin", "Tenant Admin", "tenant-a");

        assertThatThrownBy(() -> inAuthorizedQuery(tenantUser, () -> facade.candidates(
                new RoleScopeSelectionFacade.CandidateRequest("tenant:tenant-b", "organization", null, null, null))))
                .isInstanceOf(PlatformAccessDeniedException.class)
                .hasMessageContaining("not visible");
        verifyNoInteractions(tenantService, organizationService);
    }

    @Test
    void shouldVerifyTheParentBelongsToTheSelectedTenantBeforeLoadingChildren() {
        Organization parent = new Organization();
        parent.setId("organization-a");
        parent.setTenantId("tenant-a");
        parent.setEnabled(Boolean.TRUE);
        when(organizationService.requireEnabled("organization-a", "role scope organization is not active: organization-a"))
                .thenAnswer(invocation -> {
                    assertThat(TenantContext.currentTenantId()).contains("tenant-a");
                    return parent;
                });
        @SuppressWarnings("unchecked")
        PageResult<Organization> page = mock(PageResult.class);
        when(page.getRecords()).thenReturn(List.of());
        when(page.getTotal()).thenReturn(0L);
        when(page.getPageNum()).thenReturn(1);
        when(page.getPageSize()).thenReturn(20);
        when(page.getPages()).thenReturn(0L);
        when(page.isTotalKnown()).thenReturn(true);
        when(organizationService.enabledCriteria(any(Criteria.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizationService.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class))).thenReturn(page);

        var response = inAuthorizedQuery(CurrentUser.systemUser("admin", "Admin"), () -> facade.candidates(
                new RoleScopeSelectionFacade.CandidateRequest("tenant:tenant-a", "organization", null,
                        "organization-a", null)));

        assertThat(response.records()).isEmpty();
        verify(tenantService).requireActiveTenant("tenant-a");
        verify(organizationService).requireEnabled("organization-a", "role scope organization is not active: organization-a");
    }

    @Test
    void shouldMarkOnlyCurrentPageOrganizationsWithVisibleChildrenAsExpandable() {
        Organization branch = organization("organization-branch", "总机构", "root");
        Organization leaf = organization("organization-leaf", "叶机构", "root");
        @SuppressWarnings("unchecked")
        PageResult<Organization> page = mock(PageResult.class);
        when(page.getRecords()).thenReturn(List.of(branch, leaf));
        when(page.getTotal()).thenReturn(2L);
        when(page.getPageNum()).thenReturn(1);
        when(page.getPageSize()).thenReturn(20);
        when(page.getPages()).thenReturn(1L);
        when(page.isTotalKnown()).thenReturn(true);
        @SuppressWarnings("unchecked")
        PageResult<Organization> branchChildren = mock(PageResult.class);
        when(branchChildren.getRecords()).thenReturn(List.of(organization(
                "organization-child", "下级机构", "organization-branch")));
        @SuppressWarnings("unchecked")
        PageResult<Organization> noChildren = mock(PageResult.class);
        when(noChildren.getRecords()).thenReturn(List.of());
        when(organizationService.enabledCriteria(any(Criteria.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(organizationService.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(page, branchChildren, noChildren);

        var response = inAuthorizedQuery(CurrentUser.systemUser("admin", "Admin"), () -> facade.candidates(
                new RoleScopeSelectionFacade.CandidateRequest("tenant:tenant-a", "organization", null, null, null)));

        assertThat(response.records()).extracting(RoleScopeSelectionFacade.Candidate::id,
                        RoleScopeSelectionFacade.Candidate::expandable)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("organization-branch", true),
                        org.assertj.core.groups.Tuple.tuple("organization-leaf", false));
        verify(organizationService, org.mockito.Mockito.times(3))
                .pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class));
        verify(organizationService, never()).list(any(Criteria.class), any(PageRequest.class), any(Sort[].class));
    }

    @Test
    void shouldFailClosedWithoutTheQueryActionContext() {
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("admin", "Admin"))) {
            assertThatThrownBy(facade::descriptor)
                    .isInstanceOf(PlatformAccessDeniedException.class)
                    .hasMessageContaining("authorized action context");
        }
    }

    @Test
    void shouldRejectUnboundedCandidateNavigationInputBeforeItReachesTheServices() {
        assertThatThrownBy(() -> new RoleScopeSelectionFacade.CandidateRequest(
                "x".repeat(RoleScopeSelectionFacade.MAXIMUM_NAVIGATION_KEY_LENGTH + 1),
                "organization", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("navigationKey");

        assertThatThrownBy(() -> new RoleScopeSelectionFacade.CandidateRequest(
                "tenant:tenant-a", "organization", "x".repeat(RoleScopeSelectionFacade.MAXIMUM_KEYWORD_LENGTH + 1),
                null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyword");
    }

    private <T> T inAuthorizedQuery(CurrentUser user, java.util.concurrent.Callable<T> operation) {
        ActionExecutionContext context = ActionExecutionContext.ofPlatformAction(RoleService.MODULE_ALIAS,
                PlatformAction.QUERY, java.util.Set.of(), Optional.of(user));
        try (CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(user);
             ActionExecutionContextHolder.Scope ignoredAction = ActionExecutionContextHolder.use(
                     context.withAuthorizationResult(ActionAuthorizationResult.allowed(context)))) {
            try {
                return operation.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
    }

    private Organization organization(String id, String title, String parentId) {
        Organization organization = new Organization();
        organization.setId(id);
        organization.setTitle(title);
        organization.setParentId(parentId);
        organization.setTenantId("tenant-a");
        organization.setEnabled(Boolean.TRUE);
        return organization;
    }
}
