package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.spring.common.platform.ReferenceDependencyScopeCatalogResolver;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrantDao;
import net.ximatai.muyun.spring.iam.role.DataScopePolicy;
import net.ximatai.muyun.spring.iam.role.EmploymentRoleGrantDao;
import net.ximatai.muyun.spring.iam.role.GrantableAction;
import net.ximatai.muyun.spring.iam.role.Role;
import net.ximatai.muyun.spring.iam.role.RoleAction;
import net.ximatai.muyun.spring.iam.role.RoleActionDao;
import net.ximatai.muyun.spring.iam.role.RoleDao;
import net.ximatai.muyun.spring.iam.role.RoleKind;
import net.ximatai.muyun.spring.iam.role.RoleOwnerScopeType;
import net.ximatai.muyun.spring.iam.role.RolePermissionAction;
import net.ximatai.muyun.spring.iam.role.RolePermissionMatrix;
import net.ximatai.muyun.spring.iam.role.RoleAssignmentType;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.role.RoleSharePolicy;
import net.ximatai.muyun.spring.iam.role.TenantScopePolicy;
import net.ximatai.muyun.spring.iam.web.RoleWebController;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import net.ximatai.muyun.spring.platform.web.StaticModuleDefinition;
import net.ximatai.muyun.spring.platform.web.StaticModuleDefinitionScanner;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeePositionService;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleGrantableActionResolverTest {
    @Test
    void shouldFallbackToStaticRegistryWhenModuleHasNoRegisteredActions() {
        PlatformModuleActionService moduleActionService = mock(PlatformModuleActionService.class);
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        when(moduleService.resolveVisibleModule("sales.contract")).thenReturn(module("sales.contract", ModuleKind.STATIC));
        when(moduleActionService.listByModuleAliases(List.of("sales.contract"))).thenReturn(List.of());
        RoleGrantableActionResolver resolver = new RoleGrantableActionResolver(
                moduleService,
                moduleActionService,
                new StaticModuleActionRegistry(Map.of("sales.contract", List.of(PlatformAction.VIEW, PlatformAction.DELETE)))
        );

        List<GrantableAction> actions = resolver.resolve(List.of("sales.contract"));

        assertThat(actions).extracting(GrantableAction::actionCode)
                .containsExactly("view", "delete");
        assertThat(actions).extracting(GrantableAction::title)
                .containsExactly("查看", "删除");
        assertThat(actions).extracting(GrantableAction::titleKey)
                .containsExactly("platform.action.view", "platform.action.delete");
    }

    @Test
    void shouldNormalizeLegacyEnglishStandardActionTitlesButKeepModuleSpecificTitles() {
        PlatformModuleActionService moduleActionService = mock(PlatformModuleActionService.class);
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        when(moduleService.resolveVisibleModule("iam.position")).thenReturn(module("iam.position", ModuleKind.STATIC));
        when(moduleActionService.listByModuleAliases(List.of("iam.position"))).thenReturn(List.of(
                moduleAction("view", "view", "View", true),
                moduleAction("update", "update", "编辑岗位", true)
        ));
        RoleGrantableActionResolver resolver = new RoleGrantableActionResolver(moduleService, moduleActionService);

        List<GrantableAction> actions = resolver.resolve(List.of("iam.position"));

        assertThat(actions).extracting(GrantableAction::title).containsExactly("查看", "编辑岗位");
        assertThat(actions).extracting(GrantableAction::titleKey).containsExactly("platform.action.view", null);
    }

    @Test
    void shouldExposeRegisteredModuleActionsForPermissionGranting() {
        PlatformModuleActionService moduleActionService = mock(PlatformModuleActionService.class);
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        when(moduleService.resolveVisibleModule("iam.user")).thenReturn(module("iam.user", ModuleKind.STATIC));
        when(moduleActionService.listByModuleAliases(List.of("iam.user")))
                .thenReturn(List.of(moduleAction("changePassword", "changePassword", "修改密码", false)));
        RoleGrantableActionResolver resolver = new RoleGrantableActionResolver(
                moduleService,
                moduleActionService,
                new StaticModuleActionRegistry(Map.of("iam.user", List.of()))
        );

        List<GrantableAction> actions = resolver.resolve(List.of("iam.user"));

        assertThat(actions).filteredOn(action -> "changePassword".equals(action.actionCode()))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.permissionActionCode()).isEqualTo("changePassword");
                    assertThat(action.title()).isEqualTo("修改密码");
                    assertThat(action.actionAuth()).isTrue();
                    assertThat(action.dataAuth()).isFalse();
                });
    }

    @Test
    void shouldUseRegisteredModuleActionsAsStaticModuleGrantableSurface() {
        PlatformModuleActionService moduleActionService = mock(PlatformModuleActionService.class);
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        when(moduleService.resolveVisibleModule("iam.user")).thenReturn(module("iam.user", ModuleKind.STATIC));
        when(moduleActionService.listByModuleAliases(List.of("iam.user")))
                .thenReturn(List.of(
                        moduleAction("query", "view", "查询", true),
                        moduleAction("changePassword", "changePassword", "修改密码", true)
                ));
        RoleGrantableActionResolver resolver = new RoleGrantableActionResolver(
                moduleService,
                moduleActionService,
                new StaticModuleActionRegistry(Map.of("iam.user", List.of(PlatformAction.TREE, PlatformAction.REFERENCE)))
        );

        List<GrantableAction> actions = resolver.resolve(List.of("iam.user"));

        assertThat(actions).extracting(GrantableAction::actionCode)
                .containsExactly("query", "changePassword");
        assertThat(actions).extracting(GrantableAction::permissionActionCode)
                .containsExactly("view", "changePassword");
        assertThat(actions).extracting(GrantableAction::actionCode)
                .doesNotContain("tree", "reference");
    }

    @Test
    void shouldExposeSelfRegisteredStaticRoleActionsInPermissionMatrix() {
        List<PlatformModuleAction> registeredActions = scannedRoleModuleActions();
        PlatformModuleActionService moduleActionService = mock(PlatformModuleActionService.class);
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        when(moduleService.resolveVisibleModule("iam.role")).thenReturn(module("iam.role", ModuleKind.STATIC));
        when(moduleActionService.listByModuleAliases(List.of("iam.role"))).thenReturn(registeredActions);
        RoleGrantableActionResolver resolver = new RoleGrantableActionResolver(moduleService, moduleActionService);
        List<GrantableAction> grantableActions = resolver.resolve(List.of("iam.role"));
        RoleDao roleDao = mock(RoleDao.class);
        RoleActionDao roleActionDao = mock(RoleActionDao.class);
        when(roleDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(standardRole("role-1")));
        RoleAction rolePermissionsGrant = enabledAction("grant-1", "role-1", "iam.role", "rolePermissions");
        rolePermissionsGrant.setDataScopePolicy(DataScopePolicy.ORGANIZATION);
        when(roleActionDao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(List.of(rolePermissionsGrant));
        RoleService roleService = new RoleService(
                roleDao,
                mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class),
                roleActionDao,
                tenantId -> {
        },
                RoleActionGrantVerifier.platformActionsOnly(),
                mock(UserAccountService.class),
                mock(EmployeeService.class),
                mock(EmployeePositionService.class),
                mock(EmployeeAccountService.class),
                mock(OrganizationService.class),
                mock(RoleDataGrantActionDao.class),
                mock(TenantApplicationService.class),
                new StaticListableBeanFactory().getBeanProvider(ReferenceDependencyScopeCatalogResolver.class));

        RolePermissionMatrix matrix = roleService.permissionMatrix("role-1", grantableActions);

        assertThat(grantableActions).extracting(GrantableAction::actionCode)
                .contains("menu", "accountRoleGrants", "employmentRoleGrants", "rolePermissions");
        assertThat(matrix.modules()).singleElement()
                .satisfies(module -> {
                    assertThat(module.moduleAlias()).isEqualTo("iam.role");
                    assertThat(module.actions()).filteredOn(action -> "menu".equals(action.actionCode()))
                            .singleElement()
                            .extracting(RolePermissionAction::permissionActionCode,
                                    RolePermissionAction::granted,
                                    RolePermissionAction::dataAuth)
                            .containsExactly("menu", false, false);
                    assertThat(module.actions()).filteredOn(action -> "rolePermissions".equals(action.actionCode()))
                            .singleElement()
                            .extracting(RolePermissionAction::permissionActionCode,
                                    RolePermissionAction::granted,
                                    RolePermissionAction::dataScopePolicy,
                                    RolePermissionAction::dataAuth)
                            .containsExactly("rolePermissions", true, DataScopePolicy.ORGANIZATION, true);
                    assertThat(module.actions()).filteredOn(action -> "accountRoleGrants".equals(action.actionCode()))
                            .singleElement()
                            .extracting(RolePermissionAction::permissionActionCode,
                                    RolePermissionAction::granted,
                                    RolePermissionAction::dataAuth)
                            .containsExactly("accountRoleGrants", false, true);
                });
    }

    @Test
    void shouldIgnoreDisabledOrNonActionAuthRegisteredActions() {
        PlatformModuleActionService moduleActionService = mock(PlatformModuleActionService.class);
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        when(moduleService.resolveVisibleModule("sales.contract")).thenReturn(module("sales.contract", ModuleKind.DYNAMIC));
        PlatformModuleAction silent = moduleAction("silent", "silent", "Silent", false);
        silent.setActionAuth(Boolean.FALSE);
        PlatformModuleAction disabled = moduleAction("disabledSubmit", "disabledSubmit", "Disabled Submit", true);
        disabled.setEnabled(Boolean.FALSE);
        when(moduleActionService.listByModuleAliases(List.of("sales.contract"))).thenReturn(List.of(silent, disabled));
        RoleGrantableActionResolver resolver = new RoleGrantableActionResolver(moduleService, moduleActionService);

        List<GrantableAction> actions = resolver.resolve(List.of("sales.contract"));

        assertThat(actions).isEmpty();
    }

    @Test
    void shouldUseModuleActionsOnlyForDynamicModules() {
        PlatformModuleActionService moduleActionService = mock(PlatformModuleActionService.class);
        PlatformModuleService moduleService = mock(PlatformModuleService.class);
        when(moduleService.resolveVisibleModule("sales.contract")).thenReturn(module("sales.contract", ModuleKind.DYNAMIC));
        when(moduleActionService.listByModuleAliases(List.of("sales.contract")))
                .thenReturn(List.of(moduleAction("query", "view", "Query", true)));
        RoleGrantableActionResolver resolver = new RoleGrantableActionResolver(moduleService, moduleActionService);

        List<GrantableAction> actions = resolver.resolve(List.of("sales.contract", "missing.module"));

        assertThat(actions).extracting(GrantableAction::actionCode).containsExactly("query");
        assertThat(actions).extracting(GrantableAction::permissionActionCode).containsExactly("view");
    }

    private PlatformModule module(String moduleAlias, ModuleKind moduleKind) {
        PlatformModule module = new PlatformModule();
        module.setAlias(moduleAlias);
        module.setModuleKind(moduleKind);
        return module;
    }

    private List<PlatformModuleAction> scannedRoleModuleActions() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(RoleWebController.class, () -> new RoleWebController(null));
            context.refresh();
            StaticModuleDefinition definition = new StaticModuleDefinitionScanner(context).scan().stream()
                    .filter(candidate -> "iam.role".equals(candidate.moduleAlias()))
                    .findFirst()
                    .orElseThrow();
            return definition.actions().stream()
                    .map(action -> moduleAction(definition.moduleAlias(), action))
                    .toList();
        }
    }

    private PlatformModuleAction moduleAction(String actionCode, String permissionActionCode, String title, boolean dataAuth) {
        PlatformModuleAction action = new PlatformModuleAction();
        action.setModuleAlias("iam.user");
        action.setActionCode(actionCode);
        action.setPermissionActionCode(permissionActionCode);
        action.setTitle(title);
        action.setActionAuth(Boolean.TRUE);
        action.setDataAuth(dataAuth);
        action.setEnabled(Boolean.TRUE);
        return action;
    }

    private PlatformModuleAction moduleAction(String moduleAlias, StaticModuleActionDefinition definition) {
        PlatformModuleAction action = new PlatformModuleAction();
        action.setModuleAlias(moduleAlias);
        action.setActionCode(definition.actionCode());
        action.setPermissionActionCode(definition.permissionActionCode());
        action.setTitle(definition.title());
        action.setActionLevel(definition.actionLevel());
        action.setAccessMode(definition.accessMode());
        action.setActionAuth(definition.actionAuth());
        action.setDataAuth(definition.dataAuth());
        action.setDefaultGrantPolicy(definition.defaultGrantPolicy());
        action.setEnabled(Boolean.TRUE);
        return action;
    }

    private Role standardRole(String id) {
        Role role = new Role();
        role.setId(id);
        role.setTitle("Role " + id);
        role.setAssignmentType(RoleAssignmentType.EMPLOYMENT);
        role.setRoleKind(RoleKind.STANDARD);
        role.setOwnerScopeType(RoleOwnerScopeType.TENANT);
        role.setOwnerScopeId("tenant_a");
        role.setOwnerScopeKey("tenant:tenant_a");
        role.setSharePolicy(RoleSharePolicy.PRIVATE);
        role.setEnabled(Boolean.TRUE);
        return role;
    }

    private RoleAction enabledAction(String id, String roleId, String moduleAlias, String actionCode) {
        RoleAction action = new RoleAction();
        action.setId(id);
        action.setRoleId(roleId);
        action.setModuleAlias(moduleAlias);
        action.setActionCode(actionCode);
        action.setTenantScopePolicy(TenantScopePolicy.CURRENT_TENANT);
        action.setEnabled(Boolean.TRUE);
        return action;
    }
}
