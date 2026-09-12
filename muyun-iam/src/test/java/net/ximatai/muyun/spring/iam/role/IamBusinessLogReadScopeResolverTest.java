package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScope;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScopeType;
import net.ximatai.muyun.spring.ability.logging.BusinessLogContext;
import net.ximatai.muyun.spring.ability.logging.LoginLogDetails;
import net.ximatai.muyun.spring.ability.logging.LoginLogEvent;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IamBusinessLogReadScopeResolverTest {
    private static final String MODULE = "platform.business_log";
    private static final String ACTION = "viewActivity";

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    @Test
    void shouldGiveSystemUserPlatformScopeWithoutRoleLookup() {
        RoleService roleService = mock(RoleService.class);
        IamBusinessLogReadScopeResolver resolver = resolver(roleService, mock(OrganizationService.class));

        BusinessLogReadScope scope;
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("system", "System"))) {
            scope = resolver.resolve(MODULE, ACTION);
        }

        assertThat(scope.scopeType()).isEqualTo(BusinessLogReadScopeType.PLATFORM);
        assertThat(scope.isPlatformScope()).isTrue();
        verify(roleService, never()).effectiveActionGrantsWithContext("system", MODULE, ACTION);
    }

    @Test
    void shouldExpandOrganizationGrantSubtreesAndIgnoreEmploymentGrants() {
        RoleService roleService = mock(RoleService.class);
        OrganizationService organizationService = mock(OrganizationService.class);
        when(roleService.effectiveActionGrantsWithContext("user-1", MODULE, ACTION)).thenReturn(List.of(
                effectiveGrant(EffectiveRoleGrant.account("org-role", "user-1", ManagementScopeType.ORGANIZATION,
                        "organization-main")),
                effectiveGrant(EffectiveRoleGrant.account("branch-role", "user-1", ManagementScopeType.ORGANIZATION,
                        "organization-branch")),
                effectiveGrant(EffectiveRoleGrant.employment("employment-role", "position-1", "organization-hidden",
                        "department-hidden"))
        ));
        when(organizationService.selfAndDescendantIds("organization-main"))
                .thenReturn(List.of("organization-main", "organization-child"));
        when(organizationService.selfAndDescendantIds("organization-branch"))
                .thenReturn(List.of("organization-branch"));
        IamBusinessLogReadScopeResolver resolver = resolver(roleService, organizationService);

        BusinessLogReadScope scope;
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant-a", "organization-account"))) {
            scope = resolver.resolve(MODULE, ACTION);
        }

        assertThat(scope).extracting(BusinessLogReadScope::scopeType, BusinessLogReadScope::tenantId,
                        BusinessLogReadScope::permitted)
                .containsExactly(BusinessLogReadScopeType.ORGANIZATION, "tenant-a", true);
        assertThat(scope.operatorOrganizationIds()).containsExactlyInAnyOrder(
                "organization-main", "organization-child", "organization-branch");
        verify(roleService).effectiveActionGrantsWithContext("user-1", MODULE, ACTION);
        verify(organizationService).selfAndDescendantIds("organization-main");
        verify(organizationService).selfAndDescendantIds("organization-branch");
        verify(organizationService, never()).selfAndDescendantIds("organization-hidden");
    }

    @Test
    void shouldKeepOrganizationScopeInsideCurrentTenantAndRejectUnrelatedOrganization() {
        RoleService roleService = mock(RoleService.class);
        OrganizationService organizationService = mock(OrganizationService.class);
        when(roleService.effectiveActionGrantsWithContext("user-1", MODULE, ACTION)).thenReturn(List.of(
                effectiveGrant(EffectiveRoleGrant.account("org-role", "user-1", ManagementScopeType.ORGANIZATION,
                        "organization-main"))
        ));
        when(organizationService.selfAndDescendantIds("organization-main"))
                .thenReturn(List.of("organization-main", "organization-child"));
        IamBusinessLogReadScopeResolver resolver = resolver(roleService, organizationService);

        BusinessLogReadScope scope;
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            scope = resolver.resolve(MODULE, ACTION);
        }

        assertThat(scope.allows(event("tenant-a", "organization-child"))).isTrue();
        assertThat(scope.allows(event("tenant-a", "organization-other"))).isFalse();
        assertThat(scope.allows(event("tenant-b", "organization-child"))).isFalse();
    }

    @Test
    void shouldNotExpandScopeFromAGrantForAnotherAction() {
        RoleService roleService = mock(RoleService.class);
        OrganizationService organizationService = mock(OrganizationService.class);
        when(roleService.effectiveActionGrantsWithContext("user-1", MODULE, "viewLogin")).thenReturn(List.of(
                effectiveGrant(EffectiveRoleGrant.account("org-role", "user-1", ManagementScopeType.ORGANIZATION,
                        "organization-main"))
        ));
        IamBusinessLogReadScopeResolver resolver = resolver(roleService, organizationService);

        BusinessLogReadScope scope;
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            scope = resolver.resolve(MODULE, ACTION);
        }

        assertThat(scope.permitted()).isFalse();
        verify(roleService).effectiveActionGrantsWithContext("user-1", MODULE, ACTION);
        verify(organizationService, never()).selfAndDescendantIds("organization-main");
    }

    @Test
    void shouldNotElevateATenantAccountToPlatformScope() {
        RoleService roleService = mock(RoleService.class);
        when(roleService.effectiveActionGrantsWithContext("user-1", MODULE, ACTION)).thenReturn(List.of(
                effectiveGrant(EffectiveRoleGrant.account("platform-role", "user-1", ManagementScopeType.PLATFORM,
                        null))
        ));
        IamBusinessLogReadScopeResolver resolver = resolver(roleService, mock(OrganizationService.class));

        BusinessLogReadScope scope;
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            scope = resolver.resolve(MODULE, ACTION);
        }

        assertThat(scope.permitted()).isFalse();
        assertThat(scope.isPlatformScope()).isFalse();
    }

    @Test
    void shouldGrantOnlyCurrentTenantForTenantScopedAccountGrant() {
        RoleService roleService = mock(RoleService.class);
        when(roleService.effectiveActionGrantsWithContext("user-1", MODULE, ACTION)).thenReturn(List.of(
                effectiveGrant(EffectiveRoleGrant.account("tenant-role", "user-1", ManagementScopeType.TENANT,
                        "tenant-a")),
                effectiveGrant(EffectiveRoleGrant.account("other-tenant-role", "user-1", ManagementScopeType.TENANT,
                        "tenant-b"))
        ));
        IamBusinessLogReadScopeResolver resolver = resolver(roleService, mock(OrganizationService.class));

        BusinessLogReadScope scope;
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            scope = resolver.resolve(MODULE, ACTION);
        }

        assertThat(scope).extracting(BusinessLogReadScope::scopeType, BusinessLogReadScope::tenantId)
                .containsExactly(BusinessLogReadScopeType.TENANT, "tenant-a");
        assertThat(scope.allows(event("tenant-a", null))).isTrue();
        assertThat(scope.allows(event("tenant-b", "organization-any"))).isFalse();
    }

    @Test
    void shouldReturnDeniedRangeWhenNoAccountActionGrantExists() {
        RoleService roleService = mock(RoleService.class);
        when(roleService.effectiveActionGrantsWithContext("user-1", MODULE, ACTION)).thenReturn(List.of());
        IamBusinessLogReadScopeResolver resolver = resolver(roleService, mock(OrganizationService.class));

        BusinessLogReadScope scope;
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            scope = resolver.resolve(MODULE, ACTION);
        }

        assertThat(scope.permitted()).isFalse();
        assertThat(scope.allows(event("tenant-a", "organization-main"))).isFalse();
    }

    private static IamBusinessLogReadScopeResolver resolver(RoleService roleService,
                                                            OrganizationService organizationService) {
        return new IamBusinessLogReadScopeResolver(roleService, organizationService);
    }

    private static EffectiveRoleActionGrant effectiveGrant(EffectiveRoleGrant roleGrant) {
        return new EffectiveRoleActionGrant(mock(RoleAction.class), roleGrant);
    }

    private static LoginLogEvent event(String tenantId, String organizationId) {
        return new LoginLogEvent(new BusinessLogContext("event-" + organizationId, Instant.EPOCH, Instant.EPOCH,
                null, tenantId, "operator-1", organizationId, MODULE, ACTION),
                new LoginLogDetails("password", LoginLogDetails.LoginOutcome.SUCCESS, null,
                        null, null, null));
    }
}
