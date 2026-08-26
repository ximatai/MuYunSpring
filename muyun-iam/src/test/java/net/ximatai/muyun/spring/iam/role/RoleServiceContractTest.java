package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaSqlCompiler;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.form.FormControlType;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.ApplicationNotOpenedException;
import net.ximatai.muyun.spring.common.identity.BusinessPrincipal;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.ReferenceDependencyScopeCandidate;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeePosition;
import net.ximatai.muyun.spring.iam.employee.EmployeePositionService;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleServiceContractTest {
    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CurrentUserContext.clear();
    }

    @Test
    void shouldExposeRoleEnumBindings() {
        RoleService service = service(mock(RoleDao.class), mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));

        assertThat(service.querySchema().fields()).anySatisfy(field -> {
            assertThat(field.name()).isEqualTo("assignmentType");
            assertThat(field.optionBinding()).isEqualTo(OptionBinding.enumType(RoleAssignmentType.class));
            assertThat(field.optionTitleField()).isEqualTo("assignmentTypeTitle");
        });
        assertThat(service.formSchema().fields()).anySatisfy(field -> {
            assertThat(field.name()).isEqualTo("roleKind");
            assertThat(field.optionBinding()).isEqualTo(OptionBinding.enumType(RoleKind.class));
            assertThat(field.controlType()).isEqualTo(FormControlType.SELECT);
            assertThat(field.optionTitleField()).isEqualTo("roleKindTitle");
        });
        assertThat(service.formSchema().fields()).anySatisfy(field -> {
            assertThat(field.name()).isEqualTo("ownerScopeType");
            assertThat(field.optionBinding()).isEqualTo(OptionBinding.enumType(RoleOwnerScopeType.class));
            assertThat(field.controlType()).isEqualTo(FormControlType.SELECT);
            assertThat(field.optionTitleField()).isEqualTo("ownerScopeTypeTitle");
        });
        assertThat(service.formSchema().fields()).anySatisfy(field -> {
            assertThat(field.name()).isEqualTo("sharePolicy");
            assertThat(field.optionBinding()).isEqualTo(OptionBinding.enumType(RoleSharePolicy.class));
            assertThat(field.controlType()).isEqualTo(FormControlType.SELECT);
            assertThat(field.optionTitleField()).isEqualTo("sharePolicyTitle");
        });
    }

    @Test
    void shouldDefaultRoleAsEmploymentStandardAndNormalizeGroupMembers() {
        RoleDao roleDao = mock(RoleDao.class);
        when(roleDao.insert(any())).thenReturn("group-1");
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentRole("r1", RoleKind.STANDARD)))
                .thenReturn(List.of(employmentRole("r2", RoleKind.DATA_GRANT)))
                .thenReturn(List.of());
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));

        Role role = role("r0", "Standard", null, null);
        Role group = role("group-1", "Sales Group", RoleAssignmentType.ACCOUNT, RoleKind.GROUP);
        group.setMemberRoleIds(" r1, r1, r2 ");

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            service.normalizeBeforeMutation(role);
            service.insert(group);
        }

        assertThat(role.getAssignmentType()).isEqualTo(RoleAssignmentType.EMPLOYMENT);
        assertThat(role.getRoleKind()).isEqualTo(RoleKind.STANDARD);
        assertThat(group.getAssignmentType()).isEqualTo(RoleAssignmentType.EMPLOYMENT);
        assertThat(group.getMemberRoleIds()).isEqualTo("r1,r2");
        assertThat(role.getOwnerScopeType()).isEqualTo(RoleOwnerScopeType.TENANT);
        assertThat(role.getOwnerScopeId()).isEqualTo("tenant_a");
        assertThat(role.getOwnerScopeKey()).isEqualTo("tenant:tenant_a");
        assertThat(role.getSharePolicy()).isEqualTo(RoleSharePolicy.PRIVATE);
        assertThat(group.getEnabled()).isTrue();
        assertThat(group.getBuiltIn()).isFalse();
        assertThat(group.getSystemManaged()).isFalse();
    }

    @Test
    void shouldAllowPlatformRoleOnlyInSystemTenantContext() {
        RoleDao roleDao = mock(RoleDao.class);
        when(roleDao.insert(any())).thenReturn("platform-role");
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));
        Role role = accountRole("platform-role", RoleKind.STANDARD);
        role.setOwnerScopeType(RoleOwnerScopeType.PLATFORM);
        role.setSharePolicy(RoleSharePolicy.PLATFORM);

        assertThatThrownBy(() -> service.insert(role))
                .isInstanceOf(BusinessException.class)
                .hasMessage("仅系统身份可以管理平台角色")
                .hasFieldOrPropertyWithValue("code", "iam.role.platform-management-system-context-required");

        try (TenantContext.Scope ignored = TenantContext.system("test")) {
            assertThat(service.insert(role)).isEqualTo("platform-role");
        }

        verify(roleDao).insert(argThat(inserted ->
                inserted.getOwnerScopeType() == RoleOwnerScopeType.PLATFORM
                        && inserted.getOwnerScopeId() == null
                        && "platform".equals(inserted.getOwnerScopeKey())
                        && inserted.getSharePolicy() == RoleSharePolicy.PLATFORM
                        && inserted.getTenantId() == null));
    }

    @Test
    void shouldBindPlatformSharedRoleInsideTenantAndResolvePermission() {
        RoleDao roleDao = mock(RoleDao.class);
        AccountRoleGrantDao accountGrantDao = mock(AccountRoleGrantDao.class);
        RoleActionDao actionDao = mock(RoleActionDao.class);
        Role platformRole = platformRole("platform-account", RoleAssignmentType.ACCOUNT, RoleKind.STANDARD,
                RoleSharePolicy.PLATFORM);
        RoleAction action = enabledAction("ra1", "platform-account", "sales.contract", "view");
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(platformRole))
                .thenReturn(List.of())
                .thenReturn(List.of(platformRole));
        when(accountGrantDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(accountGrant("platform-account", "user-1", ManagementScopeType.TENANT,
                        "tenant_a")));
        when(accountGrantDao.insert(any())).thenReturn("grant-1");
        when(actionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(action));
        RoleService service = service(roleDao, accountGrantDao, mock(EmploymentRoleGrantDao.class), actionDao);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThat(service.grantAccountRole("platform-account", "user-1", ManagementScopeType.TENANT,
                    "tenant_a")).isEqualTo("grant-1");
            assertThat(service.hasActionPermission("user-1", "sales.contract", "query")).isTrue();
        }

        verify(accountGrantDao).insert(argThat(grant ->
                "tenant_a".equals(grant.getTenantId())
                        && "platform-account".equals(grant.getRoleId())
                        && "user-1".equals(grant.getUserId())));
    }

    @Test
    void shouldRejectTenantBindingOfPlatformPrivateRole() {
        RoleDao roleDao = mock(RoleDao.class);
        Role privateRole = platformRole("platform-private", RoleAssignmentType.ACCOUNT, RoleKind.STANDARD,
                RoleSharePolicy.PRIVATE);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(privateRole));
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.grantAccountRole(
                    "platform-private", "user-1", ManagementScopeType.TENANT, "tenant_a"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("租户不能绑定平台私有角色")
                    .satisfies(error -> assertThat(((BusinessException) error).actionMessage().code())
                            .isEqualTo("iam.role.platform-private-not-bindable"));
        }
    }

    @Test
    void shouldRejectSharePolicyOutsideOwnerScope() {
        RoleService service = service(mock(RoleDao.class), mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));
        Role tenantRole = employmentRole("tenant-role", RoleKind.STANDARD);
        tenantRole.setSharePolicy(RoleSharePolicy.PLATFORM);
        Role organizationRole = employmentRole("org-role", RoleKind.STANDARD);
        organizationRole.setOwnerScopeType(RoleOwnerScopeType.ORGANIZATION);
        organizationRole.setOwnerScopeId("org-1");
        organizationRole.setSharePolicy(RoleSharePolicy.TENANT);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.normalizeBeforeMutation(tenantRole))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("租户角色仅支持私有或租户共享策略")
                    .hasFieldOrPropertyWithValue("code", "iam.role.tenant-share-policy-invalid");
            assertThatThrownBy(() -> service.normalizeBeforeMutation(organizationRole))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("组织角色仅支持私有或归属机构及下级共享策略")
                    .hasFieldOrPropertyWithValue("code", "iam.role.organization-share-policy-invalid");
        }
    }

    @Test
    void shouldRejectTenantOwnerScopeDifferentFromCurrentTenant() {
        RoleService service = service(mock(RoleDao.class), mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));
        Role role = employmentRole("tenant-role", RoleKind.STANDARD);
        role.setOwnerScopeId("tenant_b");

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.normalizeBeforeMutation(role))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("租户角色的归属租户必须与当前租户一致")
                    .hasFieldOrPropertyWithValue("code", "iam.role.owner-tenant-mismatch");
        }
    }

    @Test
    void shouldRejectOrganizationOwnerOutsideCurrentTenant() {
        OrganizationService organizationService = mock(OrganizationService.class);
        Organization organization = new Organization();
        organization.setId("org-1");
        organization.setTenantId("tenant_b");
        organization.setEnabled(Boolean.TRUE);
        when(organizationService.requireEnabled("org-1", "role owner organization is not active: org-1"))
                .thenReturn(organization);
        RoleService service = new RoleService(mock(RoleDao.class), mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class), activeTenantVerifier(),
                RoleActionGrantVerifier.platformActionsOnly(), null, null, null, null, organizationService);
        Role role = employmentRole("org-role", RoleKind.STANDARD);
        role.setOwnerScopeType(RoleOwnerScopeType.ORGANIZATION);
        role.setOwnerScopeId("org-1");
        role.setSharePolicy(RoleSharePolicy.OWNER_AND_CHILDREN);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.normalizeBeforeMutation(role))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("角色归属机构不属于当前租户")
                    .hasFieldOrPropertyWithValue("code", "iam.role.owner-organization-tenant-mismatch");
        }
    }

    @Test
    void shouldScopeRoleSortingInsideOwnerScope() {
        RoleService service = service(mock(RoleDao.class), mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));
        Role tenantRole = employmentRole("tenant-role", RoleKind.STANDARD);

        Criteria scope = service.sortScope(tenantRole);
        String sql = new CriteriaSqlCompiler()
                .compile(scope, field -> field, DBInfo.Type.POSTGRESQL)
                .getSql();

        assertThat(sql).contains("\"ownerScopeType\" =");
        assertThat(sql).contains("\"ownerScopeKey\" =");
    }

    @Test
    void shouldRejectRoleSortingAcrossOwnerScopes() {
        RoleService service = service(mock(RoleDao.class), mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));
        Role tenantRole = employmentRole("tenant-role", RoleKind.STANDARD);
        Role sameTenantRole = employmentRole("tenant-role-2", RoleKind.STANDARD);
        Role organizationRole = organizationRole("org-role", "org-1");
        Role sameOrganizationRole = organizationRole("org-role-2", "org-1");
        Role otherOrganizationRole = organizationRole("org-role-3", "org-2");

        service.validateSortScope(tenantRole, sameTenantRole);
        service.validateSortScope(organizationRole, sameOrganizationRole);

        assertThatThrownBy(() -> service.validateSortScope(tenantRole, organizationRole))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("role sort scope must stay inside the same owner scope");
        assertThatThrownBy(() -> service.validateSortScope(organizationRole, otherOrganizationRole))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("role sort scope must stay inside the same owner scope");
    }

    @Test
    void shouldRejectAccountOrNestedGroupRoleInGroup() {
        RoleDao roleDao = mock(RoleDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(accountRole("account-role", RoleKind.STANDARD)));
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));
        Role group = employmentRole("group-1", RoleKind.GROUP);
        group.setMemberRoleIds("account-role");

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.normalizeBeforeMutation(group))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("角色组只能包含任职角色")
                    .hasFieldOrPropertyWithValue("code", "iam.role.group-member-assignment-type-invalid");
        }
    }

    @Test
    void shouldRejectMissingOrInactiveRoleGroupMember() {
        Role missingGroup = employmentRole("group-missing", RoleKind.GROUP);
        missingGroup.setMemberRoleIds("missing-role");
        RoleService missingService = service(mock(RoleDao.class), mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));

        RoleDao inactiveRoleDao = mock(RoleDao.class);
        Role inactiveRole = employmentRole("inactive-role", RoleKind.STANDARD);
        inactiveRole.setEnabled(false);
        when(inactiveRoleDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(inactiveRole));
        RoleService inactiveService = service(inactiveRoleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));
        Role inactiveGroup = employmentRole("group-inactive", RoleKind.GROUP);
        inactiveGroup.setMemberRoleIds("inactive-role");

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> missingService.normalizeBeforeMutation(missingGroup))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("角色组包含不存在的角色")
                    .hasFieldOrPropertyWithValue("code", "iam.role.group-member-not-found");
            assertThatThrownBy(() -> inactiveService.normalizeBeforeMutation(inactiveGroup))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("角色组不能包含已停用的角色")
                    .hasFieldOrPropertyWithValue("code", "iam.role.group-member-inactive");
        }
    }

    @Test
    void shouldRejectMoreThanOneDataGrantRoleInRoleGroup() {
        RoleDao roleDao = mock(RoleDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentRole("data-role-1", RoleKind.DATA_GRANT)))
                .thenReturn(List.of(employmentRole("data-role-2", RoleKind.DATA_GRANT)));
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));
        Role group = employmentRole("group-1", RoleKind.GROUP);
        group.setMemberRoleIds("data-role-1,data-role-2");

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.normalizeBeforeMutation(group))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("角色组最多只能包含一个数据授权角色")
                    .hasFieldOrPropertyWithValue("code", "iam.role.group-data-grant-member-duplicate");
        }
    }

    @Test
    void shouldRejectSystemManagedRoleMutationWithoutSystemUser() {
        RoleDao roleDao = mock(RoleDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(systemManagedRole("managed-1")));
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            Role created = employmentRole("managed-2", RoleKind.STANDARD);
            created.setSystemManaged(Boolean.TRUE);
            assertThatThrownBy(() -> service.insert(created))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("系统托管角色不能在当前上下文中修改")
                    .hasFieldOrPropertyWithValue("code", "iam.role.system-managed-mutation-denied");
            assertThatThrownBy(() -> service.update(employmentRole("managed-1", RoleKind.STANDARD)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("系统托管角色不能在当前上下文中修改")
                    .hasFieldOrPropertyWithValue("code", "iam.role.system-managed-mutation-denied");
            assertThatThrownBy(() -> service.grantAccountRole(
                    "managed-1", "user-1", ManagementScopeType.TENANT, "tenant_a"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("系统托管角色不能在当前上下文中修改")
                    .satisfies(error -> assertThat(((BusinessException) error).actionMessage().code())
                            .isEqualTo("iam.role.system-managed-mutation-denied"));
        }
    }

    @Test
    void shouldAllowSystemUserToMaintainSystemManagedRoleInTenantContext() {
        RoleDao roleDao = mock(RoleDao.class);
        RoleActionDao actionDao = mock(RoleActionDao.class);
        when(roleDao.insert(any())).thenReturn("managed-1");
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(systemManagedRole("managed-1")));
        when(actionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        when(actionDao.insert(any())).thenReturn("ra1");
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), actionDao);

        try (TenantContext.Scope ignoredTenant = TenantContext.use("tenant_a");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                     CurrentUser.systemUser("bootstrap", "Bootstrap"))) {
            Role role = systemManagedRole("managed-1");
            assertThat(service.insert(role)).isEqualTo("managed-1");
            assertThat(service.grantAction("managed-1", "sales.contract", "query")).isEqualTo(1);
        }

        verify(roleDao).insert(argThat(role ->
                Boolean.TRUE.equals(role.getSystemManaged()) && "tenant_a".equals(role.getTenantId())));
        verify(actionDao).insert(argThat(action ->
                "managed-1".equals(action.getRoleId()) && "tenant_a".equals(action.getTenantId())));
    }

    @Test
    void shouldGrantAndListAccountRolesWithoutDataScope() {
        RoleDao roleDao = mock(RoleDao.class);
        AccountRoleGrantDao accountGrantDao = mock(AccountRoleGrantDao.class);
        UserAccountService userAccountService = mock(UserAccountService.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(accountRole("r1", RoleKind.STANDARD)));
        when(accountGrantDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(accountGrant("r1", "user-1", ManagementScopeType.TENANT, "tenant_a")));
        when(accountGrantDao.insert(any())).thenReturn("grant-1");
        when(userAccountService.requireEnabled("user-1", "user account is not active: user-1"))
                .thenReturn(new UserAccount());
        RoleService service = new RoleService(roleDao, accountGrantDao, mock(EmploymentRoleGrantDao.class),
                mock(RoleActionDao.class), activeTenantVerifier(), RoleActionGrantVerifier.platformActionsOnly(),
                userAccountService, null, null, null);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThat(service.grantAccountRole("r1", "user-1", ManagementScopeType.TENANT, "tenant_a"))
                    .isEqualTo("grant-1");
            assertThat(service.userIds("r1")).containsExactly("user-1");
        }

        verify(accountGrantDao).insert(argThat(grant ->
                "r1".equals(grant.getRoleId())
                        && "user-1".equals(grant.getUserId())
                        && grant.getManagementScopeType() == ManagementScopeType.TENANT
                        && "tenant_a".equals(grant.getManagementScopeId())
                        && Boolean.TRUE.equals(grant.getEnabled())));
    }

    @Test
    void shouldRejectDataScopeOnAccountRoleAction() {
        RoleDao roleDao = mock(RoleDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(accountRole("r1", RoleKind.STANDARD)));
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));

        assertThatThrownBy(() -> service.grantAction(
                "r1", "sales.contract", "query", DataScopePolicy.OWNER, TenantScopePolicy.CURRENT_TENANT))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号角色的动作不能配置数据范围")
                .hasFieldOrPropertyWithValue("code", "iam.role.account-role-data-scope-denied");
    }

    @Test
    void shouldNotExposeDataScopeOptionForAccountRole() {
        RoleDao roleDao = mock(RoleDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(accountRole("r1", RoleKind.STANDARD)));
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));

        assertThat(service.dataScopePolicyCatalog("r1", "sales.contract").options()).isEmpty();
    }

    @Test
    void shouldExposeBackendOwnedDataScopeCatalogAndValidateReferenceDependency() {
        RoleDao roleDao = mock(RoleDao.class);
        RoleActionDao actionDao = mock(RoleActionDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentRole("r1", RoleKind.STANDARD)));
        when(actionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        when(actionDao.insert(any())).thenReturn("action-1");
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), actionDao);
        service.setReferenceDependencyScopeCatalogResolver(moduleAlias -> "sales.score".equals(moduleAlias)
                ? List.of(new ReferenceDependencyScopeCandidate(
                        "score.studentId", "学生", "school.student", "学生", "view", "查看"))
                : List.of());

        RoleDataScopePolicyCatalog catalog = service.dataScopePolicyCatalog("r1", "sales.score");

        assertThat(catalog.options()).extracting(RoleDataScopePolicyCatalog.Option::code)
                .contains(DataScopePolicy.INHERIT_DATA_GRANT, DataScopePolicy.REFERENCE_DEPENDENCY)
                .doesNotContain(DataScopePolicy.NONE);
        assertThat(catalog.options()).filteredOn(option -> option.code() == DataScopePolicy.ALL)
                .singleElement().extracting(RoleDataScopePolicyCatalog.Option::title)
                .isEqualTo("全部数据（当前租户）");
        assertThat(catalog.referenceDependencies()).singleElement()
                .extracting(RoleDataScopePolicyCatalog.ReferenceDependency::referenceFieldId)
                .isEqualTo("score.studentId");

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThat(service.grantAction("r1", "sales.score", "query",
                    DataScopePolicy.REFERENCE_DEPENDENCY, TenantScopePolicy.CURRENT_TENANT,
                    null, "score.studentId", "view")).isEqualTo(1);
        }
        verify(actionDao).insert(argThat(action -> action.getDataScopePolicy() == DataScopePolicy.REFERENCE_DEPENDENCY
                && "score.studentId".equals(action.getReferenceFieldId())
                && "view".equals(action.getReferenceActionCode())));
        assertThatThrownBy(() -> service.grantAction("r1", "sales.score", "query",
                DataScopePolicy.REFERENCE_DEPENDENCY, TenantScopePolicy.CURRENT_TENANT,
                null, "score.otherId", "view"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前模块没有可用的引用依赖数据范围")
                .hasFieldOrPropertyWithValue("code", "iam.role.reference-dependency-unavailable");
    }

    @Test
    void shouldGrantEmploymentRoleToEmployeePosition() {
        RoleDao roleDao = mock(RoleDao.class);
        EmploymentRoleGrantDao employmentGrantDao = mock(EmploymentRoleGrantDao.class);
        EmployeePositionService employeePositionService = mock(EmployeePositionService.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(employmentRole("r1", RoleKind.STANDARD)));
        when(employmentGrantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        when(employmentGrantDao.insert(any())).thenReturn("grant-1");
        when(employeePositionService.requireEnabled("position-1", "employee position is not active: position-1"))
                .thenReturn(new EmployeePosition());
        RoleService service = new RoleService(roleDao, mock(AccountRoleGrantDao.class), employmentGrantDao,
                mock(RoleActionDao.class), activeTenantVerifier(), RoleActionGrantVerifier.platformActionsOnly(),
                null, null, employeePositionService, null);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThat(service.grantEmploymentRole("r1", "position-1")).isEqualTo("grant-1");
        }

        verify(employmentGrantDao).insert(argThat(grant ->
                "r1".equals(grant.getRoleId())
                        && "position-1".equals(grant.getEmployeePositionId())
                        && Boolean.TRUE.equals(grant.getEnabled())));
    }

    @Test
    void shouldDefaultEmploymentDataActionToInheritedDataGrant() {
        RoleDao roleDao = mock(RoleDao.class);
        RoleActionDao actionDao = mock(RoleActionDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentRole("r1", RoleKind.STANDARD)));
        when(actionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        when(actionDao.insert(any())).thenReturn("action-1");
        RoleActionGrantVerifier verifier = new RoleActionGrantVerifier() {
            @Override
            public String resolveGrantablePermissionActionCode(String moduleAlias, String actionCode) {
                return actionCode;
            }

            @Override
            public boolean requiresDataScope(String moduleAlias, String actionCode) {
                return true;
            }
        };
        RoleService service = new RoleService(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), actionDao, activeTenantVerifier(), verifier,
                null, null, null, null);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            service.grantAction("r1", "sales.contract", "view");
        }

        verify(actionDao).insert(argThat(action ->
                action.getDataScopePolicy() == DataScopePolicy.INHERIT_DATA_GRANT));
    }

    @Test
    void shouldRejectNoDataScopeForEmploymentDataAction() {
        RoleDao roleDao = mock(RoleDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentRole("r1", RoleKind.STANDARD)));
        RoleActionGrantVerifier verifier = new RoleActionGrantVerifier() {
            @Override
            public String resolveGrantablePermissionActionCode(String moduleAlias, String actionCode) {
                return actionCode;
            }

            @Override
            public boolean requiresDataScope(String moduleAlias, String actionCode) {
                return true;
            }
        };
        RoleService service = new RoleService(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class), activeTenantVerifier(), verifier,
                null, null, null, null);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.grantAction("r1", "sales.contract", "view",
                    DataScopePolicy.NONE, TenantScopePolicy.CURRENT_TENANT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).actionMessage().code())
                            .isEqualTo("iam.role.employment-data-scope-required"));
        }
    }

    @Test
    void shouldRequireDataGrantRoleBeforeBindingInheritedEmploymentRole() {
        RoleDao roleDao = mock(RoleDao.class);
        EmploymentRoleGrantDao employmentGrantDao = mock(EmploymentRoleGrantDao.class);
        RoleActionDao actionDao = mock(RoleActionDao.class);
        RoleAction inheritedAction = enabledAction("action-1", "r1", "sales.contract", "view");
        inheritedAction.setDataScopePolicy(DataScopePolicy.INHERIT_DATA_GRANT);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentRole("r1", RoleKind.STANDARD)));
        when(employmentGrantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        when(actionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(inheritedAction));
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class), employmentGrantDao, actionDao);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.grantEmploymentRole("r1", "position-1"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("使用继承数据权限前，任职必须先绑定数据授权角色")
                    .satisfies(error -> assertThat(((BusinessException) error).actionMessage().code())
                            .isEqualTo("iam.role.data-grant-required"));
        }
    }

    @Test
    void shouldRequireEnabledEmployeeWhenGrantingEmploymentRole() {
        RoleDao roleDao = mock(RoleDao.class);
        EmploymentRoleGrantDao employmentGrantDao = mock(EmploymentRoleGrantDao.class);
        EmployeePositionService employeePositionService = mock(EmployeePositionService.class);
        EmployeeService employeeService = mock(EmployeeService.class);
        EmployeePosition position = employeePosition("position-1", "employee-1", "org-1", "dept-1", true);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(employmentRole("r1", RoleKind.STANDARD)));
        when(employmentGrantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        when(employmentGrantDao.insert(any())).thenReturn("grant-1");
        when(employeePositionService.requireEnabled("position-1", "employee position is not active: position-1"))
                .thenReturn(position);
        when(employeeService.requireEnabled("employee-1", "employee is not active: employee-1"))
                .thenReturn(employee("employee-1", "org-1", "dept-1", true));
        RoleService service = new RoleService(roleDao, mock(AccountRoleGrantDao.class), employmentGrantDao,
                mock(RoleActionDao.class), activeTenantVerifier(), RoleActionGrantVerifier.platformActionsOnly(),
                null, employeeService, employeePositionService, null);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            service.grantEmploymentRole("r1", "position-1");
        }

        verify(employeeService).requireEnabled("employee-1", "employee is not active: employee-1");
    }

    @Test
    void shouldRejectAccountRoleGrantedToEmploymentAndEmploymentRoleGrantedToAccount() {
        RoleDao roleDao = mock(RoleDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(accountRole("account-role", RoleKind.STANDARD)))
                .thenReturn(List.of(employmentRole("employment-role", RoleKind.STANDARD)));
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));

        assertThatThrownBy(() -> service.grantEmploymentRole("account-role", "position-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前角色不是任职角色")
                .satisfies(error -> assertThat(((BusinessException) error).actionMessage().code())
                        .isEqualTo("iam.role.not-employment-role"));
        assertThatThrownBy(() -> service.grantAccountRole(
                "employment-role", "user-1", ManagementScopeType.TENANT, "tenant_a"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前角色不是账号角色")
                .satisfies(error -> assertThat(((BusinessException) error).actionMessage().code())
                        .isEqualTo("iam.role.not-account-role"));
    }

    @Test
    void shouldRequireConcreteDataScopeOnDataGrantRole() {
        RoleDao roleDao = mock(RoleDao.class);
        RoleActionDao actionDao = mock(RoleActionDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentRole("data-role", RoleKind.DATA_GRANT)))
                .thenReturn(List.of(employmentRole("data-role", RoleKind.DATA_GRANT)));
        when(actionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        when(actionDao.insert(any())).thenReturn("ra1");
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), actionDao);

        assertThatThrownBy(() -> service.grantAction("data-role", "sales.contract", "query"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("数据授权角色必须配置具体的数据范围")
                .hasFieldOrPropertyWithValue("code", "iam.role.data-grant-scope-required");
        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThat(service.grantAction(
                    "data-role", "sales.contract", "query", DataScopePolicy.ORGANIZATION,
                    TenantScopePolicy.CURRENT_TENANT)).isEqualTo(1);
        }
    }

    @Test
    void shouldRejectMoreThanOneDataGrantRoleForSameEmployment() {
        RoleDao roleDao = mock(RoleDao.class);
        EmploymentRoleGrantDao employmentGrantDao = mock(EmploymentRoleGrantDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentRole("data-2", RoleKind.DATA_GRANT)))
                .thenReturn(List.of(employmentRole("data-1", RoleKind.DATA_GRANT)))
                .thenReturn(List.of(employmentRole("data-2", RoleKind.DATA_GRANT)));
        when(employmentGrantDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentGrant("data-1", "position-1")));
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                employmentGrantDao, mock(RoleActionDao.class));

        assertThatThrownBy(() -> service.grantEmploymentRole("data-2", "position-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("同一任职最多只能绑定一个数据授权角色")
                .hasFieldOrPropertyWithValue("code", "iam.role.data-grant-duplicate");
    }

    @Test
    void shouldResolveInheritedDataGrantActionThroughEmploymentRoleGroupMember() {
        RoleDao roleDao = mock(RoleDao.class);
        EmploymentRoleGrantDao employmentGrantDao = mock(EmploymentRoleGrantDao.class);
        RoleActionDao actionDao = mock(RoleActionDao.class);
        Role group = employmentRole("group-1", RoleKind.GROUP);
        group.setMemberRoleIds("data-role");
        RoleAction grant = enabledAction("ra1", "data-role", "sales.contract", "view");
        grant.setDataScopePolicy(DataScopePolicy.ORGANIZATION);
        when(employmentGrantDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentGrant("group-1", "position-1")));
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(group))
                .thenReturn(List.of(employmentRole("data-role", RoleKind.DATA_GRANT)));
        when(actionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(grant));
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class), employmentGrantDao, actionDao);

        RoleAction resolved = service.inheritedDataGrantAction(
                EffectiveRoleGrant.employment("standard-role", "position-1", "org-1", "dept-1"),
                "sales.contract", "query");

        assertThat(resolved).isSameAs(grant);
    }

    @Test
    void shouldResolveInheritedDataGrantActionByModuleAlias() {
        RoleDao roleDao = mock(RoleDao.class);
        EmploymentRoleGrantDao employmentGrantDao = mock(EmploymentRoleGrantDao.class);
        RoleActionDao actionDao = mock(RoleActionDao.class);
        RoleAction orderGrant = enabledAction("ra2", "data-role", "sales.order", "view");
        orderGrant.setDataScopePolicy(DataScopePolicy.DEPARTMENT);
        when(employmentGrantDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentGrant("data-role", "position-1")));
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentRole("data-role", RoleKind.DATA_GRANT)));
        when(actionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(orderGrant));
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class), employmentGrantDao, actionDao);

        RoleAction resolved = service.inheritedDataGrantAction(
                EffectiveRoleGrant.employment("standard-role", "position-1", "org-1", "dept-1"),
                "sales.order", "query");

        assertThat(resolved).isSameAs(orderGrant);
        verify(actionDao).query(argThat(criteria -> {
                    var compiled = new CriteriaSqlCompiler()
                            .compile(criteria, field -> field, DBInfo.Type.POSTGRESQL);
                    return compiled.getSql().contains("\"moduleAlias\" =")
                            && compiled.getParams().containsValue("sales.order");
                }),
                any(PageRequest.class));
    }

    @Test
    void shouldRejectRoleGroupUpdateWhenExistingEmploymentWouldHaveTwoDataGrantRoles() {
        EmploymentRoleGrantDao employmentGrantDao = mock(EmploymentRoleGrantDao.class);
        when(employmentGrantDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentGrant("group-1", "position-1")))
                .thenReturn(List.of(
                        employmentGrant("group-1", "position-1"),
                        employmentGrant("data-1", "position-1")
                ));
        RoleService service = spy(service(mock(RoleDao.class), mock(AccountRoleGrantDao.class),
                employmentGrantDao, mock(RoleActionDao.class)));
        doReturn(employmentRole("data-1", RoleKind.DATA_GRANT)).when(service).select("data-1");
        doReturn(employmentRole("data-2", RoleKind.DATA_GRANT)).when(service).select("data-2");
        Role group = employmentRole("group-1", RoleKind.GROUP);
        group.setMemberRoleIds("data-2");

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.normalizeBeforeMutation(group))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("同一任职最多只能绑定一个数据授权角色")
                    .hasFieldOrPropertyWithValue("code", "iam.role.data-grant-duplicate");
        }
    }

    @Test
    void shouldProtectSystemManagedRoleGrantsWhenDeletingByGrantId() {
        RoleDao roleDao = mock(RoleDao.class);
        AccountRoleGrantDao accountGrantDao = mock(AccountRoleGrantDao.class);
        EmploymentRoleGrantDao employmentGrantDao = mock(EmploymentRoleGrantDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(systemManagedRole("managed-1")))
                .thenReturn(List.of(systemManagedRole("managed-1")));
        RoleService service = service(roleDao, accountGrantDao, employmentGrantDao, mock(RoleActionDao.class));

        assertThatThrownBy(() -> service.deleteAccountRoleGrant("managed-1", "grant-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("系统托管角色不能在当前上下文中修改")
                .hasFieldOrPropertyWithValue("code", "iam.role.system-managed-mutation-denied");
        assertThatThrownBy(() -> service.deleteEmploymentRoleGrant("managed-1", "grant-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("系统托管角色不能在当前上下文中修改")
                .hasFieldOrPropertyWithValue("code", "iam.role.system-managed-mutation-denied");
        verify(accountGrantDao, never()).deleteById(any());
        verify(employmentGrantDao, never()).deleteById(any());
    }

    @Test
    void shouldGrantAndRevokeRoleActionAsEnabledFact() {
        RoleDao roleDao = mock(RoleDao.class);
        RoleActionDao actionDao = mock(RoleActionDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(employmentRole("r1", RoleKind.STANDARD)));
        when(actionDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(enabledAction("ra1", "r1", "sales.contract", "view")));
        when(actionDao.insert(any())).thenAnswer(invocation -> {
            invocation.<RoleAction>getArgument(0).setId("ra1");
            return "ra1";
        });
        when(actionDao.updateById(any())).thenReturn(1);
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), actionDao);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThat(service.grantAction("r1", "sales.contract", "query")).isEqualTo(1);
            assertThat(service.revokeAction("r1", "sales.contract", "query")).isEqualTo(1);
        }

        verify(actionDao).insert(argThat(action ->
                action.getId() != null
                        && "tenant_a".equals(action.getTenantId())
                        && "view".equals(action.getActionCode())
                        && action.getTenantScopePolicy() == TenantScopePolicy.CURRENT_TENANT
                        && Boolean.TRUE.equals(action.getEnabled())));
        verify(actionDao).updateById(argThat(action ->
                "tenant_a".equals(action.getTenantId())
                        && Boolean.FALSE.equals(action.getEnabled())));
    }

    @Test
    void shouldStorePermissionActionCodeReturnedByGrantVerifier() {
        RoleDao roleDao = mock(RoleDao.class);
        RoleActionDao actionDao = mock(RoleActionDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(employmentRole("r1", RoleKind.STANDARD)));
        when(actionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        when(actionDao.insert(any())).thenReturn("ra1");
        RoleActionGrantVerifier verifier = (moduleAlias, actionCode) -> {
            assertThat(moduleAlias).isEqualTo("sales.contract");
            assertThat(actionCode).isEqualTo("exportData");
            return "create";
        };
        RoleService service = new RoleService(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), actionDao, activeTenantVerifier(), verifier,
                null, null, null, null);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThat(service.grantAction("r1", "sales.contract", "exportData")).isEqualTo(1);
        }

        verify(actionDao).insert(argThat(action -> "create".equals(action.getActionCode())));
    }

    @Test
    void shouldRejectTenantRolePermissionForApplicationThatIsNotOpened() {
        RoleDao roleDao = mock(RoleDao.class);
        RoleActionDao actionDao = mock(RoleActionDao.class);
        Role role = employmentRole("r1", RoleKind.STANDARD);
        role.setTenantId("tenant_a");
        when(roleDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(role));
        TenantApplicationService tenantApplicationService = mock(TenantApplicationService.class);
        doThrow(new ApplicationNotOpenedException("tenant_a", "sales"))
                .when(tenantApplicationService).requireApplicationOpened("tenant_a", "sales");
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), actionDao);
        service.setTenantApplicationService(tenantApplicationService);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.grantAction("r1", "sales.contract", "query"))
                    .isInstanceOf(ApplicationNotOpenedException.class);
        }

        verify(actionDao, never()).insert(any());
    }

    @Test
    void shouldAuthorizeThroughEmploymentRoleGroupMembers() {
        RoleDao roleDao = mock(RoleDao.class);
        EmploymentRoleGrantDao employmentGrantDao = mock(EmploymentRoleGrantDao.class);
        RoleActionDao actionDao = mock(RoleActionDao.class);
        Role group = employmentRole("group-1", RoleKind.GROUP);
        group.setMemberRoleIds("r1");
        when(employmentGrantDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentGrant("group-1", "position-1")));
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(group))
                .thenReturn(List.of(employmentRole("r1", RoleKind.STANDARD)))
                .thenReturn(List.of(group))
                .thenReturn(List.of(employmentRole("r1", RoleKind.STANDARD)))
                .thenReturn(List.of(employmentRole("r1", RoleKind.STANDARD)));
        when(actionDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(enabledAction("ra1", "r1", "sales.contract", "view")));
        EmployeePositionService employeePositionService = mock(EmployeePositionService.class);
        when(employeePositionService.selectActiveRaw("position-1"))
                .thenReturn(employeePosition("position-1", "employee-1", "org-1", "dept-1", true));
        RoleService service = new RoleService(roleDao, mock(AccountRoleGrantDao.class), employmentGrantDao,
                actionDao, activeTenantVerifier(), RoleActionGrantVerifier.platformActionsOnly(),
                null, null, employeePositionService, null);

        BusinessPrincipal principal = BusinessPrincipal.employeePosition("employee-1", null, null, "position-1");
        assertThat(service.effectiveRoleIds(principal)).containsExactly("group-1", "r1");
        assertThat(service.hasActionPermission(principal, "sales.contract", "query")).isTrue();
        verify(employeePositionService, atLeastOnce()).selectActiveRaw("position-1");
        verify(employeePositionService, never()).select("position-1");
    }

    @Test
    void shouldAggregateEffectiveRoleGrantsFromAccountAndEmployeePositions() {
        AccountRoleGrantDao accountGrantDao = mock(AccountRoleGrantDao.class);
        EmploymentRoleGrantDao employmentGrantDao = mock(EmploymentRoleGrantDao.class);
        RoleDao roleDao = mock(RoleDao.class);
        EmployeeAccountService employeeAccountService = mock(EmployeeAccountService.class);
        EmployeeService employeeService = mock(EmployeeService.class);
        EmployeePositionService employeePositionService = mock(EmployeePositionService.class);
        when(accountGrantDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(accountGrant("account-role", "user-1", ManagementScopeType.TENANT, "tenant_a")));
        when(employeeAccountService.employeeIdOfUser("user-1")).thenReturn("employee-1");
        when(employeeService.selectActiveRaw("employee-1"))
                .thenReturn(employee("employee-1", "org-main", "dept-main", true));
        when(employeePositionService.activePositionsForRoleResolution("employee-1"))
                .thenReturn(List.of(employeePosition("position-1", "employee-1", "org-branch", "dept-branch", true)));
        when(employmentGrantDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentGrant("position-role", "position-1")));
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(accountRole("account-role", RoleKind.STANDARD)))
                .thenReturn(List.of(employmentRole("position-role", RoleKind.STANDARD)));
        RoleService service = new RoleService(roleDao, accountGrantDao, employmentGrantDao,
                mock(RoleActionDao.class), activeTenantVerifier(), RoleActionGrantVerifier.platformActionsOnly(),
                null, employeeService, employeePositionService, employeeAccountService);

        List<EffectiveRoleGrant> grants = service.effectiveRoleGrants("user-1");

        assertThat(grants).extracting(EffectiveRoleGrant::roleId)
                .containsExactly("account-role", "position-role");
        assertThat(grants.get(0))
                .extracting(EffectiveRoleGrant::sourceType, EffectiveRoleGrant::sourceId,
                        EffectiveRoleGrant::managementScopeType, EffectiveRoleGrant::managementScopeId)
                .containsExactly(RoleAssignmentType.ACCOUNT, "user-1", ManagementScopeType.TENANT, "tenant_a");
        assertThat(grants.get(1))
                .extracting(EffectiveRoleGrant::sourceType, EffectiveRoleGrant::sourceId,
                        EffectiveRoleGrant::organizationId, EffectiveRoleGrant::departmentId,
                        EffectiveRoleGrant::employeePositionId)
                .containsExactly(RoleAssignmentType.EMPLOYMENT, "position-1", "org-branch", "dept-branch", "position-1");
        verify(employeeService).selectActiveRaw("employee-1");
        verify(employeeService, never()).select("employee-1");
        verify(employeePositionService).activePositionsForRoleResolution("employee-1");
        verify(employeePositionService, never()).positions("employee-1");
    }

    @Test
    void shouldResolveInheritedDataGrantActionForEmploymentContext() {
        RoleDao roleDao = mock(RoleDao.class);
        EmploymentRoleGrantDao employmentGrantDao = mock(EmploymentRoleGrantDao.class);
        RoleActionDao actionDao = mock(RoleActionDao.class);
        RoleAction grant = enabledAction("ra1", "data-role", "sales.contract", "view");
        grant.setDataScopePolicy(DataScopePolicy.ORGANIZATION);
        when(employmentGrantDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentGrant("data-role", "position-1")));
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentRole("data-role", RoleKind.DATA_GRANT)));
        when(actionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(grant));
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class), employmentGrantDao, actionDao);

        RoleAction resolved = service.inheritedDataGrantAction(
                EffectiveRoleGrant.employment("standard-role", "position-1", "org-1", "dept-1"),
                "sales.contract", "query");

        assertThat(resolved).isSameAs(grant);
    }

    @Test
    void shouldRequireTenantContextWhenGrantingRoleFacts() {
        RoleDao roleDao = mock(RoleDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(accountRole("account-role", RoleKind.STANDARD)))
                .thenReturn(List.of(employmentRole("employment-role", RoleKind.STANDARD)));
        AccountRoleGrantDao accountGrantDao = mock(AccountRoleGrantDao.class);
        EmploymentRoleGrantDao employmentGrantDao = mock(EmploymentRoleGrantDao.class);
        when(accountGrantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        when(employmentGrantDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        RoleService service = service(roleDao, accountGrantDao, employmentGrantDao, mock(RoleActionDao.class));

        assertThatThrownBy(() -> service.grantAccountRole(
                "account-role", "user-1", ManagementScopeType.TENANT, "tenant_a"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("iam.role management requires tenant context");
        assertThatThrownBy(() -> service.grantEmploymentRole("employment-role", "position-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("iam.role management requires tenant context");
        verify(accountGrantDao, never()).insert(any());
        verify(employmentGrantDao, never()).insert(any());
    }

    @Test
    void shouldRejectRoleStructuralFieldChangesAfterCreation() {
        RoleService service = spy(service(mock(RoleDao.class), mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class)));
        doReturn(accountRole("role-1", RoleKind.STANDARD)).when(service).select("role-1");

        Role changedAssignment = employmentRole("role-1", RoleKind.STANDARD);
        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.beforeUpdate(changedAssignment))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("角色创建后不能修改分配类型")
                    .hasFieldOrPropertyWithValue("code", "iam.role.assignment-type-immutable");

            Role changedKind = accountRole("role-1", RoleKind.SYSTEM);
            assertThatThrownBy(() -> service.beforeUpdate(changedKind))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("角色创建后不能修改角色类型")
                    .hasFieldOrPropertyWithValue("code", "iam.role.kind-immutable");

            Role changedOwner = accountRole("role-1", RoleKind.STANDARD);
            changedOwner.setOwnerScopeType(RoleOwnerScopeType.ORGANIZATION);
            changedOwner.setOwnerScopeId("org-1");
            assertThatThrownBy(() -> service.beforeUpdate(changedOwner))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("角色创建后不能修改归属范围类型")
                    .hasFieldOrPropertyWithValue("code", "iam.role.owner-scope-type-immutable");

            Role existingOrganizationRole = organizationRole("role-2", "org-1");
            doReturn(existingOrganizationRole).when(service).select("role-2");
            Role changedOwnerId = organizationRole("role-2", "org-2");
            assertThatThrownBy(() -> service.beforeUpdate(changedOwnerId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("角色创建后不能修改归属范围")
                    .hasFieldOrPropertyWithValue("code", "iam.role.owner-scope-id-immutable");
        }
    }

    @Test
    void shouldReturnEffectiveActionGrantsWithRoleGrantContext() {
        RoleActionDao roleActionDao = mock(RoleActionDao.class);
        RoleAction action = enabledAction("action-1", "position-role", "sales.contract", "view");
        when(roleActionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(action));
        RoleService service = spy(service(mock(RoleDao.class), mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), roleActionDao));
        doReturn(List.of(
                EffectiveRoleGrant.employment("position-role", "position-1", "org-branch", "dept-branch"),
                EffectiveRoleGrant.employment("position-role", "position-2", "org-other", "dept-other")
        )).when(service).effectiveRoleGrants("user-1");

        List<EffectiveRoleActionGrant> grants = service.effectiveActionGrantsWithContext(
                "user-1", "sales.contract", "query");

        assertThat(grants).hasSize(2);
        assertThat(grants).allSatisfy(grant -> assertThat(grant.actionGrant()).isSameAs(action));
        assertThat(grants).extracting(grant -> grant.roleGrant().employeePositionId())
                .containsExactly("position-1", "position-2");
    }

    @Test
    void shouldKeepLegacyEffectiveActionGrantsDistinctWhenRoleHasMultipleContexts() {
        RoleActionDao roleActionDao = mock(RoleActionDao.class);
        RoleAction action = enabledAction("action-1", "position-role", "sales.contract", "view");
        when(roleActionDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(action));
        RoleService service = spy(service(mock(RoleDao.class), mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), roleActionDao));
        doReturn(List.of(
                EffectiveRoleGrant.employment("position-role", "position-1", "org-branch", "dept-branch"),
                EffectiveRoleGrant.employment("position-role", "position-2", "org-other", "dept-other")
        )).when(service).effectiveRoleGrants("user-1");

        List<RoleAction> grants = service.effectiveActionGrants("user-1", "sales.contract", "query");

        assertThat(grants).containsExactly(action);
    }

    @Test
    void shouldIgnoreDisabledEmployeeAndPositionWhenAggregatingEffectiveRoleGrants() {
        AccountRoleGrantDao accountGrantDao = mock(AccountRoleGrantDao.class);
        EmployeeAccountService employeeAccountService = mock(EmployeeAccountService.class);
        EmployeeService employeeService = mock(EmployeeService.class);
        EmployeePositionService employeePositionService = mock(EmployeePositionService.class);
        RoleDao roleDao = mock(RoleDao.class);
        when(accountGrantDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(accountGrant("account-role", "user-1", ManagementScopeType.TENANT, "tenant_a")));
        when(employeeAccountService.employeeIdOfUser("user-1")).thenReturn("employee-1");
        when(employeeService.select("employee-1")).thenReturn(employee("employee-1", "org-main", "dept-main", false));
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(accountRole("account-role", RoleKind.STANDARD)));
        RoleService service = new RoleService(roleDao, accountGrantDao, mock(EmploymentRoleGrantDao.class),
                mock(RoleActionDao.class), activeTenantVerifier(), RoleActionGrantVerifier.platformActionsOnly(),
                null, employeeService, employeePositionService, employeeAccountService);

        assertThat(service.effectiveRoleGrants("user-1")).extracting(EffectiveRoleGrant::roleId)
                .containsExactly("account-role");
    }

    @Test
    void shouldReturnAlignedActionViewWithDisabledMissingActions() {
        RoleActionDao actionDao = mock(RoleActionDao.class);
        when(actionDao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(List.of(enabledAction("ra1", "r1", "sales.contract", "query")));
        RoleService service = service(mock(RoleDao.class), mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), actionDao);

        List<RoleAction> actions = service.alignedActions(
                "r1",
                List.of("sales.contract"),
                List.of("query", "delete")
        );

        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).getActionCode()).isEqualTo("query");
        assertThat(actions.get(0).getEnabled()).isTrue();
        assertThat(actions.get(1).getActionCode()).isEqualTo("delete");
        assertThat(actions.get(1).getEnabled()).isFalse();
    }

    @Test
    void shouldBuildRolePermissionMatrixFromGrantableActions() {
        RoleDao roleDao = mock(RoleDao.class);
        RoleActionDao actionDao = mock(RoleActionDao.class);
        RoleAction viewGrant = enabledAction("ra1", "r1", "sales.contract", "view");
        viewGrant.setDataScopePolicy(DataScopePolicy.OWNER);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(employmentRole("r1", RoleKind.STANDARD)));
        when(actionDao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(List.of(viewGrant));
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), actionDao);

        RolePermissionMatrix matrix = service.permissionMatrix("r1", List.of(
                GrantableAction.ofPlatformDefaults("sales.contract", PlatformAction.QUERY),
                GrantableAction.ofPlatformDefaults("sales.contract", PlatformAction.VIEW),
                GrantableAction.ofPlatformDefaults("sales.contract", PlatformAction.TREE),
                GrantableAction.ofPlatformDefaults("sales.contract", PlatformAction.REFERENCE),
                GrantableAction.ofPlatformDefaults("sales.contract", PlatformAction.DELETE),
                GrantableAction.ofPlatformDefaults("sales.contract", PlatformAction.DISABLE),
                GrantableAction.ofPlatformDefaults("sales.contract", PlatformAction.ENABLE)
        ));

        assertThat(matrix.roleId()).isEqualTo("r1");
        assertThat(matrix.modules()).singleElement()
                .satisfies(module -> {
                    assertThat(module.moduleAlias()).isEqualTo("sales.contract");
                    assertThat(module.actions()).hasSize(3);
                    assertThat(module.actions().get(0))
                            .extracting(RolePermissionAction::actionCode,
                                    RolePermissionAction::permissionActionCode,
                                    RolePermissionAction::granted,
                                    RolePermissionAction::dataScopePolicy,
                                    RolePermissionAction::tenantScopePolicy,
                                    RolePermissionAction::dataAuth)
                            .containsExactly("view", "view", true, DataScopePolicy.OWNER,
                                    TenantScopePolicy.CURRENT_TENANT, true);
                    assertThat(module.actions().get(1))
                            .extracting(RolePermissionAction::actionCode,
                                    RolePermissionAction::permissionActionCode,
                                    RolePermissionAction::granted,
                                    RolePermissionAction::dataScopePolicy)
                            .containsExactly("delete", "delete", false, null);
                    assertThat(module.actions().get(2))
                            .extracting(RolePermissionAction::actionCode,
                                    RolePermissionAction::permissionActionCode,
                                    RolePermissionAction::granted)
                            .containsExactly("enable", "enable", false);
                });
    }

    @Test
    void shouldRejectPermissionMatrixForRoleGroup() {
        RoleDao roleDao = mock(RoleDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of(employmentRole("group-1", RoleKind.GROUP)));
        RoleService service = service(roleDao, mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class), mock(RoleActionDao.class));

        assertThatThrownBy(() -> service.permissionMatrix("group-1", List.of(
                GrantableAction.ofPlatformDefaults("sales.contract", PlatformAction.QUERY)
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessage("角色组不能直接配置动作授权")
                .hasFieldOrPropertyWithValue("code", "iam.role.group-action-grant-denied");
    }

    private RoleService service(RoleDao roleDao,
                                AccountRoleGrantDao accountRoleGrantDao,
                                EmploymentRoleGrantDao employmentRoleGrantDao,
                                RoleActionDao roleActionDao) {
        return new RoleService(roleDao, accountRoleGrantDao, employmentRoleGrantDao, roleActionDao,
                activeTenantVerifier());
    }

    private Role accountRole(String id, RoleKind kind) {
        return role(id, "Role " + id, RoleAssignmentType.ACCOUNT, kind);
    }

    private Role employmentRole(String id, RoleKind kind) {
        return role(id, "Role " + id, RoleAssignmentType.EMPLOYMENT, kind);
    }

    private Role organizationRole(String id, String organizationId) {
        Role role = employmentRole(id, RoleKind.STANDARD);
        role.setOwnerScopeType(RoleOwnerScopeType.ORGANIZATION);
        role.setOwnerScopeId(organizationId);
        role.setOwnerScopeKey("organization:" + organizationId);
        role.setSharePolicy(RoleSharePolicy.PRIVATE);
        return role;
    }

    private Role systemManagedRole(String id) {
        Role role = accountRole(id, RoleKind.STANDARD);
        role.setSystemManaged(Boolean.TRUE);
        return role;
    }

    private Role platformRole(String id,
                              RoleAssignmentType assignmentType,
                              RoleKind kind,
                              RoleSharePolicy sharePolicy) {
        Role role = role(id, "Role " + id, assignmentType, kind);
        role.setTenantId(null);
        role.setOwnerScopeType(RoleOwnerScopeType.PLATFORM);
        role.setOwnerScopeId(null);
        role.setOwnerScopeKey("platform");
        role.setSharePolicy(sharePolicy);
        return role;
    }

    private Role role(String id, String title, RoleAssignmentType assignmentType, RoleKind kind) {
        Role role = new Role();
        role.setId(id);
        role.setTitle(title);
        role.setAssignmentType(assignmentType);
        role.setRoleKind(kind);
        role.setOwnerScopeType(RoleOwnerScopeType.TENANT);
        role.setOwnerScopeId("tenant_a");
        role.setOwnerScopeKey("tenant:tenant_a");
        role.setSharePolicy(RoleSharePolicy.PRIVATE);
        role.setEnabled(Boolean.TRUE);
        return role;
    }

    private AccountRoleGrant accountGrant(String roleId,
                                          String userId,
                                          ManagementScopeType scopeType,
                                          String scopeId) {
        AccountRoleGrant grant = new AccountRoleGrant();
        grant.setRoleId(roleId);
        grant.setUserId(userId);
        grant.setManagementScopeType(scopeType);
        grant.setManagementScopeId(scopeId);
        grant.setEnabled(Boolean.TRUE);
        return grant;
    }

    private EmploymentRoleGrant employmentGrant(String roleId, String employeePositionId) {
        EmploymentRoleGrant grant = new EmploymentRoleGrant();
        grant.setRoleId(roleId);
        grant.setEmployeePositionId(employeePositionId);
        grant.setEnabled(Boolean.TRUE);
        return grant;
    }

    private Employee employee(String id, String organizationId, String departmentId, boolean enabled) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setOrganizationId(organizationId);
        employee.setDepartmentId(departmentId);
        employee.setEnabled(enabled);
        return employee;
    }

    private EmployeePosition employeePosition(String id, String employeeId, String organizationId,
                                              String departmentId, boolean enabled) {
        EmployeePosition position = new EmployeePosition();
        position.setId(id);
        position.setEmployeeId(employeeId);
        position.setOrganizationId(organizationId);
        position.setDepartmentId(departmentId);
        position.setEnabled(enabled);
        return position;
    }

    private RoleAction enabledAction(String id, String roleId, String moduleAlias, String actionCode) {
        RoleAction action = new RoleAction();
        action.setId(id);
        action.setRoleId(roleId);
        action.setModuleAlias(moduleAlias);
        action.setActionCode(actionCode);
        action.setTenantId("tenant_a");
        action.setTenantScopePolicy(TenantScopePolicy.CURRENT_TENANT);
        action.setDataScopePolicy(DataScopePolicy.NONE);
        action.setEnabled(Boolean.TRUE);
        return action;
    }

    private ActiveTenantVerifier activeTenantVerifier() {
        return mock(ActiveTenantVerifier.class);
    }
}
