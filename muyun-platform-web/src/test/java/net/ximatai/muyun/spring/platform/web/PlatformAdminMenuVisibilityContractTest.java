package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.StaticModuleDefinitionRegistrar;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;

import net.ximatai.muyun.spring.platform.support.TestMemoryDao;

import net.ximatai.muyun.spring.ability.initialdata.InitialDataAbility;
import net.ximatai.muyun.spring.iam.role.PlatformRoleActionGrantVerifier;
import net.ximatai.muyun.spring.platform.web.workflow.WorkflowRuntimeAdminWebController;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrant;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrantDao;
import net.ximatai.muyun.spring.iam.role.EmploymentRoleGrant;
import net.ximatai.muyun.spring.iam.role.EmploymentRoleGrantDao;
import net.ximatai.muyun.spring.iam.role.RoleAction;
import net.ximatai.muyun.spring.iam.role.RoleActionDao;
import net.ximatai.muyun.spring.iam.role.RoleDao;
import net.ximatai.muyun.spring.iam.role.RoleMenuVisibilityPolicyService;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.iam.tenant.TenantDao;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.iam.user.PasswordHashingService;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountDao;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleConfigArchiveFacade;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleHealthService;
import net.ximatai.muyun.spring.platform.config.LowCodeModulePackageExchangeService;
import net.ximatai.muyun.spring.platform.config.LowCodeModulePackageImportService;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleTemplateService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryCategoryService;
import net.ximatai.muyun.spring.platform.dictionary.DictionaryItemService;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataExecutor;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuScheme;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.menu.SystemMenuSchemeAccessPolicy;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionDao;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleDao;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowActionPolicyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PlatformAdminMenuVisibilityContractTest {
    private final TenantMemoryDao tenantDao = new TenantMemoryDao();
    private final UserAccountMemoryDao userAccountDao = new UserAccountMemoryDao();
    private final RoleMemoryDao roleDao = new RoleMemoryDao();
    private final AccountRoleGrantMemoryDao accountRoleGrantDao = new AccountRoleGrantMemoryDao();
    private final EmploymentRoleGrantMemoryDao employmentRoleGrantDao = new EmploymentRoleGrantMemoryDao();
    private final RoleActionMemoryDao roleActionDao = new RoleActionMemoryDao();
    private final PlatformModuleMemoryDao moduleDao = new PlatformModuleMemoryDao();
    private final PlatformModuleActionMemoryDao moduleActionDao = new PlatformModuleActionMemoryDao();
    private final TestMemoryDao<MenuScheme> schemeDao = new TestMemoryDao<>();
    private final TestMemoryDao<Menu> menuDao = new TestMemoryDao<>();

    private final TenantService tenantService = new TenantService(tenantDao);
    private final UserAccountService userAccountService = net.ximatai.muyun.spring.iam.support.UserAccountServiceTestFactory.create(
            userAccountDao,
            tenantService,
            new PasswordHashingService()
    );
    private final PlatformModuleService moduleService = new PlatformModuleService(moduleDao);
    private final PlatformModuleActionService moduleActionService = new PlatformModuleActionService(
            moduleActionDao,
            moduleService
    );
    private final RoleService roleService = new RoleService(
            roleDao,
            accountRoleGrantDao,
            employmentRoleGrantDao,
            roleActionDao,
            tenantService,
            new PlatformRoleActionGrantVerifier(moduleService, moduleActionService),
            userAccountService,
            null,
            null,
            null
    );
    private final MenuSchemeService schemeService = new MenuSchemeService(
            schemeDao,
            java.util.Optional.empty(),
            SystemMenuSchemeAccessPolicy.DENY_ALL
    );
    private final MenuService menuService = new MenuService(
            menuDao,
            schemeService,
            moduleService,
            java.util.Optional.of(new RoleMenuVisibilityPolicyService(roleService))
    );

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
        TenantContext.clear();
    }

    @Test
    void platformMenuModulesShouldRegisterMenuActionAndBecomeVisibleForSystemAdmin() {
        try (GenericApplicationContext context = platformEntryContext()) {
            registerStaticModules(context);
            initializePlatformData(context);

            Set<String> menuModuleAliases = menuModuleAliases(context);
            assertThat(menuModuleAliases).contains(
                    "platform.application",
                    "platform.low_code_governance",
                    WorkflowActionPolicyService.MANAGEMENT_MODULE_ALIAS
            );

            assertThat(menuModuleAliases)
                    .allSatisfy(moduleAlias -> assertThat(moduleActionService.findByModuleAliasAndActionCode(
                            moduleAlias,
                            PlatformAction.MENU.code()
                    )).as(moduleAlias + " menu action").isNotNull());

            try (TenantContext.Scope ignoredTenant = TenantContext.system("system admin menu test");
                 CurrentUserContext.Scope ignoredUser = CurrentUserContext.use(platformSuperAdmin())) {
                assertThat(visibleModuleAliases()).containsAll(menuModuleAliases);
            }
        }
    }

    private GenericApplicationContext platformEntryContext() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.registerBean(ApplicationWebController.class);
        context.registerBean(DictionaryCategoryService.class,
                () -> new DictionaryCategoryService(mock(BaseDao.class)));
        context.registerBean(DictionaryCategoryWebController.class, () -> {
            DictionaryCategoryWebController controller = new DictionaryCategoryWebController();
            ReflectionTestUtils.setField(controller, "service", context.getBean(DictionaryCategoryService.class));
            return controller;
        });
        context.registerBean(DictionaryItemService.class,
                () -> new DictionaryItemService(mock(BaseDao.class), context.getBean(DictionaryCategoryService.class)));
        context.registerBean(DictionaryItemWebController.class, () -> {
            DictionaryItemWebController controller = new DictionaryItemWebController();
            ReflectionTestUtils.setField(controller, "service", context.getBean(DictionaryItemService.class));
            return controller;
        });
        context.registerBean(WorkflowRuntimeAdminWebController.class,
                () -> new WorkflowRuntimeAdminWebController(null));
        context.registerBean(LowCodeModuleConfigArchiveFacade.class,
                () -> mock(LowCodeModuleConfigArchiveFacade.class));
        context.registerBean(LowCodeModuleHealthService.class, () -> mock(LowCodeModuleHealthService.class));
        context.registerBean(LowCodeModulePackageExchangeService.class,
                () -> mock(LowCodeModulePackageExchangeService.class));
        context.registerBean(LowCodeModulePackageImportService.class,
                () -> mock(LowCodeModulePackageImportService.class));
        context.registerBean(LowCodeModuleTemplateService.class,
                () -> mock(LowCodeModuleTemplateService.class));
        context.registerBean(LowCodeGovernanceWebController.class);
        context.refresh();
        return context;
    }

    private Set<String> menuModuleAliases(GenericApplicationContext context) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        for (String beanName : context.getBeanNamesForAnnotation(PlatformMenu.class)) {
            Class<?> beanClass = AopUtils.getTargetClass(context.getBean(beanName));
            PlatformStaticModule module = AnnotationUtils.findAnnotation(beanClass, PlatformStaticModule.class);
            if (module != null) {
                aliases.add(module.alias());
            }
        }
        return aliases;
    }

    private void registerStaticModules(GenericApplicationContext context) {
        new StaticModuleDefinitionRegistrar(
                moduleService,
                moduleActionService,
                List.of(),
                List.of(new StaticModuleDefinitionScanner(context))
        ).registerAll();
    }

    private void initializePlatformData(GenericApplicationContext context) {
        new InitialDataExecutor(
                List.<InitialDataAbility<?>>of(
                        schemeService,
                        menuService,
                        userAccountService
                ),
                List.of(new PlatformMenuInitialDataDeclarationProvider(menuService, context))
        ).initializeAll();
    }

    private CurrentUser platformSuperAdmin() {
        return CurrentUser.systemUser(
                UserAccountService.PLATFORM_SUPER_ADMIN_USER_ID,
                UserAccountService.PLATFORM_SUPER_ADMIN_USERNAME
        );
    }

    private Set<String> visibleModuleAliases() {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        for (Menu root : menuService.currentUserVisibleRootMenus()) {
            appendVisibleModuleAliases(aliases, root);
        }
        return aliases;
    }

    private void appendVisibleModuleAliases(Set<String> aliases, Menu menu) {
        if (menu.getModuleAlias() != null && !menu.getModuleAlias().isBlank()) {
            aliases.add(menu.getModuleAlias());
        }
        for (Menu child : menuService.visibleChildren(MenuSchemeService.ADMIN_SCHEME_ID, menu.getId())) {
            appendVisibleModuleAliases(aliases, child);
        }
    }

    private static class TenantMemoryDao extends TestMemoryDao<Tenant> implements TenantDao {
    }

    private static class UserAccountMemoryDao extends TestMemoryDao<UserAccount> implements UserAccountDao {
    }

    private static class RoleMemoryDao extends TestMemoryDao<net.ximatai.muyun.spring.iam.role.Role>
            implements RoleDao {
    }

    private static class AccountRoleGrantMemoryDao extends TestMemoryDao<AccountRoleGrant>
            implements AccountRoleGrantDao {
    }

    private static class EmploymentRoleGrantMemoryDao extends TestMemoryDao<EmploymentRoleGrant>
            implements EmploymentRoleGrantDao {
    }

    private static class RoleActionMemoryDao extends TestMemoryDao<RoleAction> implements RoleActionDao {
    }

    private static class PlatformModuleMemoryDao extends TestMemoryDao<PlatformModule> implements PlatformModuleDao {
    }

    private static class PlatformModuleActionMemoryDao extends TestMemoryDao<PlatformModuleAction>
            implements PlatformModuleActionDao {
    }
}
