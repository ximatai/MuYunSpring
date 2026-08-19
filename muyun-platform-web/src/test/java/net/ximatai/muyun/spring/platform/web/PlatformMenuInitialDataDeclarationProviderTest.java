package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.StaticModuleDefinitionRegistrar;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;

import net.ximatai.muyun.spring.platform.support.TestMemoryDao;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.ability.initialdata.InitialDataAbility;
import net.ximatai.muyun.spring.iam.web.DepartmentWebController;
import net.ximatai.muyun.spring.iam.web.EmployeeWebController;
import net.ximatai.muyun.spring.iam.web.OrganizationWebController;
import net.ximatai.muyun.spring.iam.web.PasswordPolicyRuleWebController;
import net.ximatai.muyun.spring.iam.web.PositionCategoryWebController;
import net.ximatai.muyun.spring.iam.web.PositionWebController;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataConflictException;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataDeclaration;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataExecutor;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuOpenMode;
import net.ximatai.muyun.spring.platform.menu.MenuPageMode;
import net.ximatai.muyun.spring.platform.menu.MenuScheme;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.menu.MenuScopeType;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class PlatformMenuInitialDataDeclarationProviderTest {
    private final TestMemoryDao<MenuScheme> schemeDao = new TestMemoryDao<>();
    private final TestMemoryDao<Menu> menuDao = new TestMemoryDao<>();
    private final TestMemoryDao<PlatformModule> moduleDao = new TestMemoryDao<>();
    private final MenuSchemeService schemeService = new MenuSchemeService(schemeDao);
    private final PlatformModuleService moduleService = new PlatformModuleService(moduleDao);
    private final MenuService menuService = new MenuService(
            menuDao,
            schemeService,
            moduleService,
            Optional.of((moduleAlias, currentUser) -> true)
    );

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
        TenantContext.clear();
    }

    @Test
    void shouldRegisterPlatformAdminSchemeGroupsAndModuleMenus() {
        try (GenericApplicationContext context = context(PlatformModuleWeb.class, IamRoleWeb.class, HiddenModuleWeb.class)) {
            registerStaticModules(context);
            initializePlatformMenus(context);

            MenuScheme scheme = schemeService.select(MenuSchemeService.ADMIN_SCHEME_ID);
            assertThat(scheme).satisfies(value -> {
                assertThat(value.getAlias()).isEqualTo(MenuSchemeService.ADMIN_SCHEME_ALIAS);
                assertThat(value.getScopeType()).isEqualTo(MenuScopeType.SYSTEM);
                assertThat(value.getScopeId()).isEqualTo(MenuSchemeService.SYSTEM_SCOPE_ID);
                assertThat(value.getTitle()).isEqualTo("平台超管");
            });
            assertThat(menuService.rootMenus(scheme.getId()))
                    .extracting(Menu::getId)
                    .containsExactly(PlatformMenuGroups.PLATFORM);
            assertThat(menuService.children(scheme.getId(), PlatformMenuGroups.PLATFORM))
                    .extracting(Menu::getId)
                    .containsExactly(
                            PlatformMenuGroups.MODELING,
                            PlatformMenuGroups.IDENTITY,
                            PlatformMenuGroups.BUSINESS_SUPPORT,
                            PlatformMenuGroups.SECURITY_AUDIT,
                            PlatformMenuGroups.OPS,
                            PlatformMenuGroups.SETTINGS
                    );
            assertThat(menuService.children(scheme.getId(), PlatformMenuGroups.PLATFORM))
                    .extracting(Menu::getId)
                    .allSatisfy(id -> assertThat(id.length()).isLessThanOrEqualTo(PlatformAbilityFields.TREE_PARENT_LENGTH));
            assertThat(menuService.children(scheme.getId(), PlatformMenuGroups.MODELING))
                    .extracting(Menu::getModuleAlias)
                    .containsExactly("platform.module");
            Menu platformModuleMenu = moduleMenu("platform.module");
            assertThat(platformModuleMenu.getId())
                    .isEqualTo("platform.menu.module.platform.module");
            assertThat(platformModuleMenu).satisfies(menu -> {
                assertThat(menu.getSystemManaged()).isTrue();
                assertThat(menu.getOpenMode()).isEqualTo(MenuOpenMode.TAB);
                assertThat(menu.getModuleAlias()).isEqualTo("platform.module");
                assertThat(menu.getRoute()).isNull();
                assertThat(menu.getPageMode()).isEqualTo(MenuPageMode.LIST);
                assertThat(menu.getTitle()).isEqualTo("模块管理");
                assertThat(menu.getSortOrder()).isEqualTo(20);
            });
            assertThat(menuService.children(scheme.getId(), PlatformMenuGroups.IDENTITY))
                    .extracting(Menu::getModuleAlias)
                    .containsExactly("iam.role");
            assertThat(moduleMenu("platform.hidden")).isNull();
        }
    }

    @Test
    void shouldDisableSystemManagedMenuWhoseCodeContributionWasRemoved() {
        try (GenericApplicationContext context = context(PlatformModuleWeb.class)) {
            registerStaticModules(context);
            initializePlatformMenus(context);
            Menu stale = new Menu();
            stale.setId("platform.menu.module.retired");
            stale.setSchemeId(MenuSchemeService.ADMIN_SCHEME_ID);
            stale.setParentId(PlatformMenuGroups.MODELING);
            stale.setTitle("已撤销模块");
            stale.setEnabled(Boolean.TRUE);
            stale.setSystemManaged(Boolean.TRUE);
            PlatformManagedMutationContext.runAsPlatformManaged(() -> menuService.insert(stale));

            new PlatformMenuContributionReconciliationTask(
                    menuService, new PlatformMenuInitialDataDeclarationProvider(menuService, context)).run();

            assertThat(menuService.select(stale.getId()).getEnabled()).isFalse();
            assertThat(moduleMenu("platform.module").getEnabled()).isTrue();
        }
    }

    @Test
    void shouldRegisterPasswordManagementUnderSecurityAndAudit() {
        try (GenericApplicationContext context = context(PasswordPolicyRuleWebController.class)) {
            registerStaticModules(context);
            initializePlatformMenus(context);

            assertThat(menuService.select(PlatformMenuGroups.SECURITY_AUDIT)).satisfies(menu -> {
                assertThat(menu.getTitle()).isEqualTo("安全与审计");
                assertThat(menu.getParentId()).isEqualTo(PlatformMenuGroups.PLATFORM);
            });
            assertThat(moduleMenu("iam.password_policy_rule")).satisfies(menu -> {
                assertThat(menu.getTitle()).isEqualTo("密码管理");
                assertThat(menu.getParentId()).isEqualTo(PlatformMenuGroups.SECURITY_AUDIT);
                assertThat(menu.getRoute()).isNull();
                assertThat(menu.getPageMode()).isEqualTo(MenuPageMode.LIST);
            });
        }
    }

    @Test
    void shouldUsePlatformMenuAnnotationOpenMode() {
        try (GenericApplicationContext context = context(WindowModuleWeb.class)) {
            registerStaticModules(context);
            initializePlatformMenus(context);

            assertThat(moduleMenu("platform.window")).satisfies(menu -> {
                assertThat(menu.getOpenMode()).isEqualTo(MenuOpenMode.WINDOW);
            });
        }
    }

    @Test
    void shouldRegisterRouteMenuWhenStaticModuleDeclaresRoute() {
        try (GenericApplicationContext context = context(RouteModuleWeb.class)) {
            registerStaticModules(context);
            initializePlatformMenus(context);

            assertThat(moduleMenu("platform.route")).satisfies(menu -> {
                assertThat(menu.getOpenMode()).isEqualTo(MenuOpenMode.TAB);
                assertThat(menu.getModuleAlias()).isEqualTo("platform.route");
                assertThat(menu.getRoute()).isEqualTo("/platform/routes");
                assertThat(menu.getPageMode()).isNull();
            });
        }
    }

    @Test
    void shouldRegisterOrganizationMenuAsStandardModuleEntry() {
        try (GenericApplicationContext context = context(OrganizationWebController.class)) {
            registerStaticModules(context);
            initializePlatformMenus(context);

            assertThat(moduleMenu("iam.organization")).satisfies(menu -> {
                assertThat(menu.getOpenMode()).isEqualTo(MenuOpenMode.TAB);
                assertThat(menu.getModuleAlias()).isEqualTo("iam.organization");
                assertThat(menu.getRoute()).isNull();
                assertThat(menu.getPageMode()).isEqualTo(MenuPageMode.LIST);
            });
        }
    }

    @Test
    void shouldKeepModuleMenuUnchangedWhenReinitializing() {
        try (GenericApplicationContext context = context(PlatformModuleWeb.class)) {
            registerStaticModules(context);
            initializePlatformMenus(context);
            Menu existing = moduleMenu("platform.module");
            Integer version = existing.getVersion();

            initializePlatformMenus(context);

            assertThat(moduleMenu("platform.module")).satisfies(menu -> {
                assertThat(menu.getPageMode()).isEqualTo(MenuPageMode.LIST);
                assertThat(menu.getVersion()).isEqualTo(version);
            });
        }
    }

    @Test
    void shouldPreserveOperatorFieldsWhenRegisteredMenusExist() {
        try (GenericApplicationContext context = context(PlatformModuleWeb.class)) {
            registerStaticModules(context);
            initializePlatformMenus(context);

            Menu existing = moduleMenu("platform.module");
            existing.setTitle("旧标题");
            existing.setSortOrder(999);
            menuService.update(existing);

            initializePlatformMenus(context);

            assertThat(menuService.rootMenus(MenuSchemeService.ADMIN_SCHEME_ID)).hasSize(1);
            assertThat(menuService.children(MenuSchemeService.ADMIN_SCHEME_ID, PlatformMenuGroups.PLATFORM))
                    .hasSize(6);
            assertThat(menuService.children(MenuSchemeService.ADMIN_SCHEME_ID, PlatformMenuGroups.MODELING))
                    .singleElement()
                    .satisfies(menu -> {
                        assertThat(menu.getTitle()).isEqualTo("旧标题");
                        assertThat(menu.getSortOrder()).isEqualTo(999);
                    });
        }
    }

    @Test
    void shouldRepairManagedMenuStructureDrift() {
        try (GenericApplicationContext context = context(PlatformModuleWeb.class)) {
            registerStaticModules(context);
            initializePlatformMenus(context);

            Menu group = menuDao.findById(PlatformMenuGroups.MODELING);
            group.setParentId("wrong-parent");
            group.setOpenMode(MenuOpenMode.TAB);
            group.setRoute("/wrong");
            Menu moduleMenu = moduleMenu("platform.module");
            moduleMenu.setOpenMode(MenuOpenMode.TAB);
            moduleMenu.setExternalUrl("https://example.com");

            initializePlatformMenus(context);

            assertThat(menuService.select(PlatformMenuGroups.MODELING)).satisfies(repaired -> {
                assertThat(repaired.getSchemeId()).isEqualTo(MenuSchemeService.ADMIN_SCHEME_ID);
                assertThat(repaired.getParentId()).isEqualTo(PlatformMenuGroups.PLATFORM);
                assertThat(repaired.getOpenMode()).isNull();
                assertThat(repaired.getRoute()).isNull();
            });
            assertThat(moduleMenu("platform.module")).satisfies(repaired -> {
                assertThat(repaired.getSchemeId()).isEqualTo(MenuSchemeService.ADMIN_SCHEME_ID);
                assertThat(repaired.getParentId()).isEqualTo(PlatformMenuGroups.MODELING);
                assertThat(repaired.getOpenMode()).isEqualTo(MenuOpenMode.TAB);
                assertThat(repaired.getModuleAlias()).isEqualTo("platform.module");
                assertThat(repaired.getExternalUrl()).isNull();
            });
        }
    }

    @Test
    void shouldRejectManagedMenuSchemeDrift() {
        try (GenericApplicationContext context = context(PlatformModuleWeb.class)) {
            registerStaticModules(context);
            initializePlatformMenus(context);

            Menu moduleMenu = moduleMenu("platform.module");
            moduleMenu.setSchemeId("wrong-scheme");

            assertThatThrownBy(() -> initializePlatformMenus(context))
                    .isInstanceOf(InitialDataConflictException.class)
                    .hasMessageContaining(moduleMenu.getId() + ".schemeId");
        }
    }

    @Test
    void shouldRejectSoftDeletedManagedMenu() {
        try (GenericApplicationContext context = context(PlatformModuleWeb.class)) {
            registerStaticModules(context);
            initializePlatformMenus(context);

            Menu moduleMenu = moduleMenu("platform.module");
            moduleMenu.setDeleted(Boolean.TRUE);

            assertThatThrownBy(() -> initializePlatformMenus(context))
                    .isInstanceOf(InitialDataConflictException.class)
                    .hasMessageContaining("soft-deleted: " + moduleMenu.getId());
        }
    }

    @Test
    void shouldExposeRegisteredPlatformAdminMenusToSystemUser() {
        try (GenericApplicationContext context = context(PlatformModuleWeb.class, IamRoleWeb.class)) {
            registerStaticModules(context);
            initializePlatformMenus(context);

            try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                    CurrentUser.systemUser("system-user", "System"))) {
                List<Menu> roots = menuService.currentUserVisibleRootMenus();

                assertThat(roots).extracting(Menu::getId)
                        .containsExactly(PlatformMenuGroups.PLATFORM);
            }
        }
    }

    @Test
    void shouldDeclareMenusForCoreAdministrationEntryPoints() {
        assertMenu(ApplicationWebController.class, PlatformMenuGroups.MODELING, "应用管理", 10);
        assertMenu(OrganizationWebController.class, PlatformMenuGroups.IDENTITY, "", 20);
        assertMenu(DepartmentWebController.class, PlatformMenuGroups.IDENTITY, "部门管理", 30);
        assertMenu(PositionWebController.class, PlatformMenuGroups.IDENTITY, "岗位管理", 40);
        assertMenu(EmployeeWebController.class, PlatformMenuGroups.IDENTITY, "职员管理", 50);
    }

    @Test
    void shouldDeclareTheCanonicalPositionMenuIdentity() {
        try (GenericApplicationContext context = context(PositionWebController.class)) {
            PlatformMenuInitialDataDeclarationProvider provider =
                    new PlatformMenuInitialDataDeclarationProvider(menuService, context);

            assertThat(provider.declaredMenuIds()).contains("platform.menu.module.iam.position")
                    .doesNotContain("platform.menu.module.iam.position_category");
        }
    }

    @Test
    void shouldRejectPlatformMenuWithoutStaticModule() {
        try (GenericApplicationContext context = context(InvalidMenuWeb.class)) {
            assertThatThrownBy(() -> initializePlatformMenus(context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@PlatformMenu requires @PlatformStaticModule");
        }
    }

    @Test
    void shouldDelayMenuDeclarationExistingLookupUntilExecution() {
        MenuScheme scheme = new MenuScheme();
        scheme.setId(MenuSchemeService.ADMIN_SCHEME_ID);
        scheme.setAlias(MenuSchemeService.ADMIN_SCHEME_ALIAS);
        scheme.setScopeType(MenuScopeType.SYSTEM);
        scheme.setScopeId(MenuSchemeService.SYSTEM_SCOPE_ID);
        schemeDao.insert(scheme);
        Menu platform = menu(
                PlatformMenuGroups.PLATFORM,
                MenuSchemeService.ADMIN_SCHEME_ID,
                TreeAbility.ROOT_ID
        );
        menuDao.insert(platform);
        Menu parent = menu(
                PlatformMenuGroups.MODELING,
                MenuSchemeService.ADMIN_SCHEME_ID,
                PlatformMenuGroups.PLATFORM
        );
        menuDao.insert(parent);
        PlatformModule module = new PlatformModule();
        module.setAlias("platform.module");
        module.setApplicationAlias("platform");
        module.setTitle("平台模块");
        moduleDao.insert(module);
        Menu existing = menu(
                "platform.menu.module.platform.module",
                MenuSchemeService.ADMIN_SCHEME_ID,
                PlatformMenuGroups.MODELING
        );
        existing.setModuleAlias("old.module");
        menuDao.insert(existing);
        Menu desired = menu(
                "platform.menu.module.platform.module",
                MenuSchemeService.ADMIN_SCHEME_ID,
                PlatformMenuGroups.MODELING
        );
        desired.setModuleAlias("platform.module");
        desired.setOpenMode(MenuOpenMode.TAB);

        InitialDataDeclaration<Menu> declaration = InitialDataDeclaration.reconcileManaged(menuService, desired);

        assertThat(existing.getModuleAlias()).isEqualTo("old.module");

        new InitialDataExecutor(List.of(), List.of(() -> List.of(declaration))).initializeAll();

        assertThat(existing.getModuleAlias()).isEqualTo("platform.module");
    }

    private void initializePlatformMenus(GenericApplicationContext context) {
        new InitialDataExecutor(List.<InitialDataAbility<?>>of(schemeService, menuService), List.of(
                new PlatformMenuInitialDataDeclarationProvider(menuService, context)
        )).initializeAll();
    }

    private void registerStaticModules(GenericApplicationContext context) {
        new StaticModuleDefinitionRegistrar(
                moduleService,
                mock(PlatformModuleActionService.class),
                List.of(),
                List.of(new StaticModuleDefinitionScanner(context))
        ).registerAll();
    }

    @SafeVarargs
    private GenericApplicationContext context(Class<?>... beanClasses) {
        GenericApplicationContext context = new GenericApplicationContext();
        for (Class<?> beanClass : beanClasses) {
            context.registerBean(beanClass);
        }
        context.refresh();
        return context;
    }

    private Menu menu(String id, String schemeId, String parentId) {
        Menu menu = new Menu();
        menu.setId(id);
        menu.setSchemeId(schemeId);
        menu.setParentId(parentId);
        menu.setTitle("模块管理");
        menu.setEnabled(Boolean.TRUE);
        menu.setSortOrder(20);
        return menu;
    }

    private Menu moduleMenu(String moduleAlias) {
        return menuDao.query(Criteria.of().eq("moduleAlias", moduleAlias), PageRequest.of(1, 1))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void assertMenu(Class<?> controllerType, String parent, String title, int order) {
        PlatformMenu menu = controllerType.getAnnotation(PlatformMenu.class);
        assertThat(menu).isNotNull();
        assertThat(menu.parent()).isEqualTo(parent);
        assertThat(menu.title()).isEqualTo(title);
        assertThat(menu.order()).isEqualTo(order);
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = "platform.module", title = "平台模块")
    @PlatformMenu(parent = PlatformMenuGroups.MODELING, title = "模块管理", order = 20)
    @RequestMapping("/platform.module")
    static class PlatformModuleWeb {
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class, alias = "iam.role", title = "角色管理")
    @PlatformMenu(parent = PlatformMenuGroups.IDENTITY, order = 10)
    @RequestMapping("/iam.role")
    static class IamRoleWeb {
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = "platform.hidden", title = "隐藏模块")
    @RequestMapping("/platform.hidden")
    static class HiddenModuleWeb {
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = "platform.window", title = "窗口模块")
    @PlatformMenu(parent = PlatformMenuGroups.MODELING, openMode = MenuOpenMode.WINDOW)
    @RequestMapping("/platform.window")
    static class WindowModuleWeb {
    }

    @RestController
    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = "platform.route", title = "路由模块",
            route = "/platform/routes")
    @PlatformMenu(parent = PlatformMenuGroups.MODELING)
    @RequestMapping("/platform.route")
    static class RouteModuleWeb {
    }

    @RestController
    @PlatformMenu(parent = PlatformMenuGroups.MODELING)
    static class InvalidMenuWeb {
    }
}
