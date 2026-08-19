package net.ximatai.muyun.spring.platform.menu;

import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.common.exception.PlatformConfigurationException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.MenuVisibilityPolicyService;
import net.ximatai.muyun.spring.common.platform.OrganizationHierarchyService;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MenuServiceContractTest {
    private final TestMemoryDao<MenuScheme> schemeDao = new TestMemoryDao<>();
    private final TestMemoryDao<Menu> menuDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformModule> moduleDao = new TestMemoryDao<>();
    private final PlatformModuleService moduleService = new PlatformModuleService(moduleDao);
    private final MenuSchemeService schemeService = new MenuSchemeService(schemeDao);
    private final MenuService menuService = new MenuService(menuDao, schemeService, moduleService);

    @BeforeEach
    void setUpModules() {
        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            insertModule("crm.customer");
            insertModule("crm.contract");
        }
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
        TenantContext.clear();
    }

    @Test
    void shouldCreateTenantMenuSchemeWithScopeAliasAndTenantIsolation() {
        String tenantASchemeId;
        String tenantBSchemeId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantASchemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            tenantBSchemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(schemeService.select(tenantASchemeId)).isNotNull();
            assertThat(schemeService.select(tenantBSchemeId)).isNull();
            assertThat(schemeService.select(tenantASchemeId).getScopeId()).isEqualTo("tenant-a");
        }
    }

    @Test
    void shouldRejectDuplicateSchemeAliasWithinSameScope() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            schemeService.insert(scheme("default", MenuScopeType.TENANT, null));

            assertThatThrownBy(() -> schemeService.insert(scheme("default", MenuScopeType.TENANT, null)))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("unique");
        }
    }

    @Test
    void shouldCreateSystemSchemeWithoutTenant() {
        String schemeId;
        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            schemeId = schemeService.insert(scheme("admin_default", MenuScopeType.SYSTEM, null));
        }

        MenuScheme saved = schemeService.select(schemeId);
        assertThat(saved.getTenantId()).isNull();
        assertThat(saved.getScopeId()).isEqualTo(MenuSchemeService.SYSTEM_SCOPE_ID);
    }

    @Test
    void shouldRequireSystemContextForSystemScheme() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> schemeService.insert(scheme("admin_default", MenuScopeType.SYSTEM, null)))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("platform.menu-scheme.system-context-required"))
                    .hasMessageContaining("system context");
        }
    }

    @Test
    void shouldRejectSchemeIdentityChanges() {
        String schemeId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            schemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            MenuScheme changedAlias = scheme("mobile", MenuScopeType.TENANT, "tenant-a");
            changedAlias.setId(schemeId);
            changedAlias.setVersion(0);
            assertThatThrownBy(() -> schemeService.update(changedAlias))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("platform.menu-scheme.identity-immutable"))
                    .hasMessageContaining("identity");

            MenuScheme changedScope = scheme("default", MenuScopeType.ORGANIZATION, "org-a");
            changedScope.setId(schemeId);
            changedScope.setTenantId("tenant-a");
            changedScope.setVersion(0);
            assertThatThrownBy(() -> schemeService.update(changedScope))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("platform.menu-scheme.identity-immutable"))
                    .hasMessageContaining("identity");
        }
    }

    @Test
    void shouldCreateSchemeScopedMenuTreeAndRejectUnscopedRootLookup() {
        String schemeId;
        String rootId;
        String childId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            schemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            rootId = menuService.insert(groupMenu(schemeId, "客户中心", TreeAbility.ROOT_ID));
            childId = menuService.insert(moduleMenu(schemeId, "客户", rootId, "crm.customer"));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(menuService.rootMenus(schemeId)).extracting(Menu::getId).containsExactly(rootId);
            assertThat(menuService.children(schemeId, rootId)).extracting(Menu::getId).containsExactly(childId);
            assertThatThrownBy(() -> menuService.children(TreeAbility.ROOT_ID))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("rootMenus");
        }
    }

    @Test
    void shouldIsolateMenusBetweenTenants() {
        String tenantASchemeId;
        String tenantBSchemeId;
        String tenantAMenuId;
        String tenantBMenuId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantASchemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            tenantAMenuId = menuService.insert(moduleMenu(tenantASchemeId, "客户", TreeAbility.ROOT_ID, "crm.customer"));
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-b")) {
            tenantBSchemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            tenantBMenuId = menuService.insert(moduleMenu(tenantBSchemeId, "客户", TreeAbility.ROOT_ID, "crm.customer"));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(menuService.rootMenus(tenantASchemeId)).extracting(Menu::getId).containsExactly(tenantAMenuId);
            assertThat(menuService.rootMenus(tenantBSchemeId)).isEmpty();
            assertThat(menuService.select(tenantBMenuId)).isNull();
        }
    }

    @Test
    void shouldAllowSameModuleMountedByDifferentMenus() {
        String firstSchemeId;
        String secondSchemeId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            firstSchemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            secondSchemeId = schemeService.insert(scheme("mobile", MenuScopeType.TENANT, null));
            menuService.insert(moduleMenu(firstSchemeId, "客户", TreeAbility.ROOT_ID, "crm.customer"));
            menuService.insert(moduleMenu(secondSchemeId, "客户移动端", TreeAbility.ROOT_ID, "crm.customer"));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(menuService.rootMenus(firstSchemeId)).extracting(Menu::getModuleAlias).containsExactly("crm.customer");
            assertThat(menuService.rootMenus(secondSchemeId)).extracting(Menu::getModuleAlias).containsExactly("crm.customer");
        }
    }

    @Test
    void shouldRejectParentAcrossSchemesAndInvalidTargets() {
        String firstSchemeId;
        String secondSchemeId;
        String firstRootId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            firstSchemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            secondSchemeId = schemeService.insert(scheme("mobile", MenuScopeType.TENANT, null));
            firstRootId = menuService.insert(groupMenu(firstSchemeId, "客户中心", TreeAbility.ROOT_ID));

            assertThatThrownBy(() -> menuService.insert(moduleMenu(secondSchemeId, "错误节点", firstRootId, "crm.customer")))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("same scheme");
            assertThatThrownBy(() -> menuService.insert(moduleMenu(firstSchemeId, "错误模块", TreeAbility.ROOT_ID, "customer")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("moduleAlias");
            assertThatThrownBy(() -> menuService.insert(moduleMenu(firstSchemeId, "不存在模块", TreeAbility.ROOT_ID, "crm.unknown")))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code()).isEqualTo("platform.menu.module-not-found"))
                    .hasMessageContaining("existing module");
            Menu moduleWithRoute = moduleMenu(firstSchemeId, "客户看板", TreeAbility.ROOT_ID, "crm.customer");
            moduleWithRoute.setRoute("/customer");
            String moduleWithRouteId = menuService.insert(moduleWithRoute);
            assertThat(menuService.select(moduleWithRouteId)).satisfies(menu -> {
                assertThat(menu.getModuleAlias()).isEqualTo("crm.customer");
                assertThat(menu.getRoute()).isEqualTo("/customer");
                assertThat(menu.getPageMode()).isNull();
            });
            Menu groupWithOpenMode = groupMenu(firstSchemeId, "错误分组打开方式", TreeAbility.ROOT_ID);
            groupWithOpenMode.setOpenMode(MenuOpenMode.TAB);
            assertThatThrownBy(() -> menuService.insert(groupWithOpenMode))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("platform.menu.container-open-mode-denied"))
                    .hasMessageContaining("Container menu cannot have openMode");
            Menu moduleWithoutOpenMode = moduleMenu(firstSchemeId, "错误模块打开方式", TreeAbility.ROOT_ID, "crm.customer");
            moduleWithoutOpenMode.setOpenMode(null);
            assertThatThrownBy(() -> menuService.insert(moduleWithoutOpenMode))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code()).isEqualTo("platform.menu.open-mode-required"))
                    .hasMessageContaining("Module entry menu requires openMode");
        }
    }

    @Test
    void shouldRejectMenuSchemeChange() {
        String firstSchemeId;
        String secondSchemeId;
        String menuId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            firstSchemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            secondSchemeId = schemeService.insert(scheme("mobile", MenuScopeType.TENANT, null));
            menuId = menuService.insert(moduleMenu(firstSchemeId, "客户", TreeAbility.ROOT_ID, "crm.customer"));
            Menu moving = moduleMenu(secondSchemeId, "客户", TreeAbility.ROOT_ID, "crm.customer");
            moving.setId(menuId);
            moving.setVersion(0);

            assertThatThrownBy(() -> menuService.update(moving))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("scheme");
        }
    }

    @Test
    void shouldRejectDeletingMenuSchemeWhenMenusExist() {
        AtomicReference<MenuService> menuServiceReference = new AtomicReference<>();
        MenuSchemeService guardedSchemeService = new MenuSchemeService(
                schemeDao,
                Optional.empty(),
                SystemMenuSchemeAccessPolicy.DENY_ALL,
                menuServiceReference::get
        );
        MenuService guardedMenuService = new MenuService(menuDao, guardedSchemeService, moduleService);
        menuServiceReference.set(guardedMenuService);

        String schemeId;
        String emptySchemeId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            schemeId = guardedSchemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            guardedMenuService.insert(moduleMenu(schemeId, "客户", TreeAbility.ROOT_ID, "crm.customer"));
            emptySchemeId = guardedSchemeService.insert(scheme("empty", MenuScopeType.TENANT, null));

            assertThatThrownBy(() -> guardedSchemeService.delete(schemeId))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("platform.menu-scheme.delete-with-menus-denied"))
                    .hasMessageContaining("menus exist");

            assertThat(guardedSchemeService.delete(emptySchemeId)).isEqualTo(1);
        }
    }

    @Test
    void shouldProjectManagedMenuMutationBoundaryToRecordActions() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            String schemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            Menu managed = groupMenu(schemeId, "平台菜单", TreeAbility.ROOT_ID);
            managed.setSystemManaged(Boolean.TRUE);
            PlatformManagedMutationContext.runAsPlatformManaged(() -> menuService.insert(managed));

            assertThat(menuService.ordinaryRecordActionAvailability("update", managed))
                    .hasValueSatisfying(decision -> assertThat(decision.reason()).isEqualTo("平台托管记录不可编辑"));
            assertThat(menuService.ordinaryRecordActionAvailability("delete", managed))
                    .hasValueSatisfying(decision -> assertThat(decision.reason()).isEqualTo("平台托管记录不可删除"));
            assertThat(menuService.ordinaryRecordActionAvailability("enable", managed)).isEmpty();
        }
    }

    @Test
    void shouldReorderMenusWithinSameSchemeAndParent() {
        String schemeId;
        String firstId;
        String secondId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            schemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            firstId = menuService.insert(moduleMenu(schemeId, "客户", TreeAbility.ROOT_ID, "crm.customer"));
            secondId = menuService.insert(moduleMenu(schemeId, "合同", TreeAbility.ROOT_ID, "crm.contract"));

            menuService.reorder(List.of(secondId, firstId));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(menuService.rootMenus(schemeId))
                    .extracting(Menu::getId)
                    .containsExactly(secondId, firstId);
        }
    }

    @Test
    void shouldPruneMenuTreeByModuleVisibilityWithoutChangingMenuModel() {
        MenuVisibilityPolicyService visibility = (moduleAlias, currentUser) -> "crm.customer".equals(moduleAlias);
        MenuService scopedMenuService = new MenuService(menuDao, schemeService, moduleService, Optional.of(visibility));
        String schemeId;
        String rootId;
        String customerId;
        String contractId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            schemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            rootId = scopedMenuService.insert(groupMenu(schemeId, "业务中心", TreeAbility.ROOT_ID));
            customerId = scopedMenuService.insert(moduleMenu(schemeId, "客户", rootId, "crm.customer"));
            contractId = scopedMenuService.insert(moduleMenu(schemeId, "合同", rootId, "crm.contract"));

            assertThat(scopedMenuService.visibleRootMenus(schemeId)).extracting(Menu::getId).containsExactly(rootId);
            assertThat(scopedMenuService.visibleChildren(schemeId, rootId)).extracting(Menu::getId).containsExactly(customerId);
            assertThat(scopedMenuService.children(schemeId, rootId)).extracting(Menu::getId)
                    .containsExactly(customerId, contractId);
        }
    }

    @Test
    void shouldTreatRouteMenuWithModuleAliasAsVisibleModuleEntry() {
        MenuVisibilityPolicyService visibility = (moduleAlias, currentUser) ->
                "crm.route_customer".equals(moduleAlias) || "crm.external_docs".equals(moduleAlias);
        MenuService scopedMenuService = new MenuService(menuDao, schemeService, moduleService, Optional.of(visibility));
        String schemeId;
        String rootId;
        String customerId;
        String contractId;
        String docsId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            insertRouteModule("crm.route_customer", "/crm/customers");
            insertRouteModule("crm.route_contract", "/crm/contracts");
            insertLinkModule("crm.external_docs", "https://example.com/docs");
            schemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            rootId = scopedMenuService.insert(groupMenu(schemeId, "业务中心", TreeAbility.ROOT_ID));
            customerId = scopedMenuService.insert(routeMenu(
                    schemeId, "客户", rootId, "crm.route_customer"));
            contractId = scopedMenuService.insert(routeMenu(
                    schemeId, "合同", rootId, "crm.route_contract"));
            docsId = scopedMenuService.insert(linkMenu(schemeId, "文档", rootId, "crm.external_docs"));

            assertThat(scopedMenuService.visibleChildren(schemeId, rootId))
                    .extracting(Menu::getId)
                    .containsExactly(customerId, docsId);
            assertThat(scopedMenuService.currentUserVisibleModuleMenu("crm.route_customer").getId())
                    .isEqualTo(customerId);
            assertThat(scopedMenuService.currentUserVisibleModuleMenu("crm.external_docs").getId())
                    .isEqualTo(docsId);
            assertThat(scopedMenuService.currentUserVisibleModuleMenu("crm.route_contract"))
                    .isNull();
            assertThat(scopedMenuService.children(schemeId, rootId))
                    .extracting(Menu::getId)
                    .containsExactly(customerId, contractId, docsId);
        }
    }

    @Test
    void shouldProjectRouteMenuFromModuleEntry() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            insertRouteModule("crm.route_customer", " /crm/customers ");
            String schemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            String menuId = menuService.insert(routeMenu(
                    schemeId, "客户", TreeAbility.ROOT_ID, "crm.route_customer"));

            assertThat(menuService.select(menuId)).satisfies(menu -> {
                assertThat(menu.getRoute()).isEqualTo("/crm/customers");
                assertThat(menu.getModuleAlias()).isEqualTo("crm.route_customer");
                assertThat(menu.getPageMode()).isNull();
            });
        }
    }

    @Test
    void shouldValidateRouteModuleInternalPath() {
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> insertRouteModule("crm.bad_route", "crm/customers"))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("internal path");
            assertThatThrownBy(() -> insertRouteModule("crm.external_route", "https://example.com/customers"))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("internal path");
            assertThatThrownBy(() -> insertRouteModule("crm.protocol_relative", "//example.com/customers"))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("internal path");
        }
    }

    @Test
    void shouldFailClosedForModuleMenusWhenNoVisibilityPolicyExists() {
        String schemeId;
        String rootId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            schemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            rootId = menuService.insert(groupMenu(schemeId, "业务中心", TreeAbility.ROOT_ID));
            menuService.insert(moduleMenu(schemeId, "客户", rootId, "crm.customer"));

            assertThat(menuService.visibleRootMenus(schemeId)).isEmpty();
        }
    }

    @Test
    void shouldHideGroupWhenAllDescendantsArePruned() {
        MenuVisibilityPolicyService visibility = (moduleAlias, currentUser) -> false;
        MenuService scopedMenuService = new MenuService(menuDao, schemeService, moduleService, Optional.of(visibility));
        String schemeId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            schemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            String rootId = scopedMenuService.insert(groupMenu(schemeId, "业务中心", TreeAbility.ROOT_ID));
            scopedMenuService.insert(moduleMenu(schemeId, "客户", rootId, "crm.customer"));

            assertThat(scopedMenuService.visibleRootMenus(schemeId)).isEmpty();
        }
    }

    @Test
    void shouldInferCurrentUserMenuSchemeWithoutFrontendInput() {
        MenuVisibilityPolicyService visibility = (moduleAlias, currentUser) -> "crm.contract".equals(moduleAlias);
        MenuService scopedMenuService = new MenuService(menuDao, schemeService, moduleService, Optional.of(visibility));
        String tenantSchemeId;
        String organizationSchemeId;
        String tenantMenuId;
        String organizationMenuId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantSchemeId = schemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            tenantMenuId = scopedMenuService.insert(moduleMenu(tenantSchemeId, "客户", TreeAbility.ROOT_ID, "crm.customer"));
            MenuScheme organizationScheme = scheme("org_default", MenuScopeType.ORGANIZATION, "org-1");
            organizationScheme.setTenantId("tenant-a");
            organizationSchemeId = schemeService.insert(organizationScheme);
            organizationMenuId = scopedMenuService.insert(moduleMenu(
                    organizationSchemeId, "合同", TreeAbility.ROOT_ID, "crm.contract"));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                     CurrentUser.tenantUser("user-1", "User", "tenant-a", "org-1"))) {
            assertThat(scopedMenuService.currentUserVisibleRootMenus())
                    .extracting(Menu::getId)
                    .containsExactly(organizationMenuId);
        }
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                     CurrentUser.tenantUser("user-2", "User", "tenant-a"))) {
            assertThat(scopedMenuService.currentUserVisibleRootMenus())
                    .extracting(Menu::getId)
                    .doesNotContain(tenantMenuId);
        }
    }

    @Test
    void shouldInheritCurrentUserMenuSchemeFromAncestorOrganization() {
        OrganizationHierarchyService organizationHierarchy = organizationId -> {
            if ("dept-1".equals(organizationId)) {
                return List.of("dept-1", "group-1", "root-org");
            }
            return List.of(organizationId);
        };
        MenuSchemeService hierarchySchemeService = new MenuSchemeService(schemeDao, Optional.of(organizationHierarchy));
        MenuService scopedMenuService = new MenuService(menuDao, hierarchySchemeService, moduleService,
                Optional.of((moduleAlias, currentUser) -> true));
        String tenantSchemeId;
        String ancestorSchemeId;
        String ancestorMenuId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            tenantSchemeId = hierarchySchemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            scopedMenuService.insert(moduleMenu(tenantSchemeId, "客户", TreeAbility.ROOT_ID, "crm.customer"));
            MenuScheme ancestorScheme = scheme("group_default", MenuScopeType.ORGANIZATION, "group-1");
            ancestorScheme.setTenantId("tenant-a");
            ancestorSchemeId = hierarchySchemeService.insert(ancestorScheme);
            ancestorMenuId = scopedMenuService.insert(moduleMenu(
                    ancestorSchemeId, "合同", TreeAbility.ROOT_ID, "crm.contract"));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                     CurrentUser.tenantUser("user-1", "User", "tenant-a", "dept-1"))) {
            assertThat(scopedMenuService.currentUserVisibleRootMenus())
                    .extracting(Menu::getId)
                    .containsExactly(ancestorMenuId);
        }
    }

    @Test
    void shouldPreferCurrentOrganizationSchemeBeforeAncestorAndTenantSchemes() {
        OrganizationHierarchyService organizationHierarchy = organizationId -> List.of("dept-1", "group-1", "root-org");
        MenuSchemeService hierarchySchemeService = new MenuSchemeService(schemeDao, Optional.of(organizationHierarchy));
        MenuService scopedMenuService = new MenuService(menuDao, hierarchySchemeService, moduleService,
                Optional.of((moduleAlias, currentUser) -> true));
        String currentMenuId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            String tenantSchemeId = hierarchySchemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            scopedMenuService.insert(moduleMenu(tenantSchemeId, "租户客户", TreeAbility.ROOT_ID, "crm.customer"));

            MenuScheme ancestorScheme = scheme("group_default", MenuScopeType.ORGANIZATION, "group-1");
            ancestorScheme.setTenantId("tenant-a");
            String ancestorSchemeId = hierarchySchemeService.insert(ancestorScheme);
            scopedMenuService.insert(moduleMenu(ancestorSchemeId, "上级合同", TreeAbility.ROOT_ID, "crm.contract"));

            MenuScheme currentScheme = scheme("dept_default", MenuScopeType.ORGANIZATION, "dept-1");
            currentScheme.setTenantId("tenant-a");
            String currentSchemeId = hierarchySchemeService.insert(currentScheme);
            currentMenuId = scopedMenuService.insert(moduleMenu(
                    currentSchemeId, "本部门客户", TreeAbility.ROOT_ID, "crm.customer"));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                     CurrentUser.tenantUser("user-1", "User", "tenant-a", "dept-1"))) {
            assertThat(scopedMenuService.currentUserVisibleRootMenus())
                    .extracting(Menu::getId)
                    .containsExactly(currentMenuId);
        }
    }

    @Test
    void shouldFallbackToTenantMenuSchemeWhenOrganizationChainHasNoScheme() {
        OrganizationHierarchyService organizationHierarchy = organizationId -> List.of("dept-1", "group-1", "root-org");
        MenuSchemeService hierarchySchemeService = new MenuSchemeService(schemeDao, Optional.of(organizationHierarchy));
        MenuService scopedMenuService = new MenuService(menuDao, hierarchySchemeService, moduleService,
                Optional.of((moduleAlias, currentUser) -> true));
        String tenantMenuId;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            String tenantSchemeId = hierarchySchemeService.insert(scheme("default", MenuScopeType.TENANT, null));
            tenantMenuId = scopedMenuService.insert(moduleMenu(
                    tenantSchemeId, "租户客户", TreeAbility.ROOT_ID, "crm.customer"));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                     CurrentUser.tenantUser("user-1", "User", "tenant-a", "dept-1"))) {
            assertThat(scopedMenuService.currentUserVisibleRootMenus())
                    .extracting(Menu::getId)
                    .containsExactly(tenantMenuId);
        }
    }

    @Test
    void shouldAllowSystemUserToUseSystemMenuScheme() {
        MenuService scopedMenuService = new MenuService(menuDao, schemeService, moduleService,
                Optional.of((moduleAlias, currentUser) -> true));
        String systemMenuId;
        try (TenantContext.Scope ignored = TenantContext.system("test system menu scheme")) {
            String systemSchemeId = schemeService.insert(scheme(
                    "platform_admin", MenuScopeType.SYSTEM, MenuSchemeService.SYSTEM_SCOPE_ID));
            systemMenuId = scopedMenuService.insert(moduleMenu(
                    systemSchemeId, "平台客户", TreeAbility.ROOT_ID, "crm.customer"));
        }

        try (TenantContext.Scope ignored = TenantContext.system("test system user menu");
             CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                     CurrentUser.systemUser("platform.user.super_admin", "admin"))) {
            assertThat(scopedMenuService.currentUserVisibleRootMenus())
                    .extracting(Menu::getId)
                    .containsExactly(systemMenuId);
        }
    }

    @Test
    void shouldRejectTenantUserWhenTenantHasNoMenuScheme() {
        MenuService scopedMenuService = new MenuService(menuDao, schemeService, moduleService,
                Optional.of((moduleAlias, currentUser) -> true));
        try (TenantContext.Scope ignored = TenantContext.system("test system menu scheme")) {
            String systemSchemeId = schemeService.insert(scheme(
                    "platform_admin", MenuScopeType.SYSTEM, MenuSchemeService.SYSTEM_SCOPE_ID));
            scopedMenuService.insert(moduleMenu(
                    systemSchemeId, "平台客户", TreeAbility.ROOT_ID, "crm.customer"));
        }

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
            CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(
                     CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            assertThatThrownBy(scopedMenuService::currentUserVisibleRootMenus)
                    .isInstanceOf(PlatformConfigurationException.class)
                    .hasMessageContaining("menu scheme is not configured for current user");
        }
    }

    private MenuScheme scheme(String alias, MenuScopeType scopeType, String scopeId) {
        MenuScheme scheme = new MenuScheme();
        scheme.setAlias(alias);
        scheme.setScopeType(scopeType);
        scheme.setScopeId(scopeId);
        scheme.setTitle(alias);
        return scheme;
    }

    private Menu groupMenu(String schemeId, String title, String parentId) {
        Menu menu = new Menu();
        menu.setSchemeId(schemeId);
        menu.setTitle(title);
        menu.setParentId(parentId);
        return menu;
    }

    private Menu moduleMenu(String schemeId, String title, String parentId, String moduleAlias) {
        Menu menu = new Menu();
        menu.setSchemeId(schemeId);
        menu.setTitle(title);
        menu.setParentId(parentId);
        menu.setOpenMode(MenuOpenMode.TAB);
        menu.setModuleAlias(moduleAlias);
        return menu;
    }

    private Menu routeMenu(String schemeId, String title, String parentId, String moduleAlias) {
        Menu menu = new Menu();
        menu.setSchemeId(schemeId);
        menu.setTitle(title);
        menu.setParentId(parentId);
        menu.setOpenMode(MenuOpenMode.TAB);
        menu.setModuleAlias(moduleAlias);
        return menu;
    }

    private Menu linkMenu(String schemeId, String title, String parentId, String moduleAlias) {
        Menu menu = new Menu();
        menu.setSchemeId(schemeId);
        menu.setTitle(title);
        menu.setParentId(parentId);
        menu.setOpenMode(MenuOpenMode.TAB);
        menu.setModuleAlias(moduleAlias);
        return menu;
    }

    private void insertModule(String alias) {
        PlatformModule module = new PlatformModule();
        module.setApplicationAlias(alias.substring(0, alias.indexOf('.')));
        module.setAlias(alias);
        module.setTitle(alias);
        moduleService.insert(module);
    }

    private void insertRouteModule(String alias, String route) {
        PlatformModule module = new PlatformModule();
        module.setApplicationAlias(alias.substring(0, alias.indexOf('.')));
        module.setAlias(alias);
        module.setTitle(alias);
        module.setEntryType(ModuleEntryType.ROUTE);
        module.setEntryRoute(route);
        moduleService.insert(module);
    }

    private void insertLinkModule(String alias, String externalUrl) {
        PlatformModule module = new PlatformModule();
        module.setApplicationAlias(alias.substring(0, alias.indexOf('.')));
        module.setAlias(alias);
        module.setTitle(alias);
        module.setEntryType(ModuleEntryType.LINK);
        module.setEntryExternalUrl(externalUrl);
        moduleService.insert(module);
    }
}
