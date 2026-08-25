package net.ximatai.muyun.spring.platform.menu;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuDao;
import net.ximatai.muyun.spring.platform.menu.MenuOpenMode;
import net.ximatai.muyun.spring.platform.menu.MenuScheme;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeDao;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.menu.MenuScopeType;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultTenantMenuProvisionerTest {
    private static final int STANDARD_ID_MAX_LENGTH = 32;

    private final MenuSchemeMemoryDao schemeDao = new MenuSchemeMemoryDao();
    private final MenuMemoryDao menuDao = new MenuMemoryDao();
    private final PlatformModuleService moduleService = mock(PlatformModuleService.class);
    private final MenuSchemeService schemeService = new MenuSchemeService(schemeDao);
    private final MenuService menuService = new MenuService(menuDao, schemeService, moduleService, Optional.empty());
    private final DefaultTenantMenuProvisioner provisioner = new DefaultTenantMenuProvisioner(schemeService, menuService);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldCreateTenantAdminSchemeAndCopySystemAdminMenus() {
        when(moduleService.resolveVisibleModule("iam.user")).thenReturn(module("iam.user"));
        createSystemAdminMenuTree();

        provisioner.afterTenantCreated("demo");
        provisioner.afterTenantCreated("demo");

        String schemeId = DefaultTenantMenuProvisioner.tenantAdminSchemeId("demo");
        try (TenantContext.Scope ignored = TenantContext.use("demo")) {
            MenuScheme scheme = schemeService.select(schemeId);
            assertThat(scheme).isNotNull();
            assertThat(scheme.getAlias()).isEqualTo(DefaultTenantMenuProvisioner.TENANT_ADMIN_SCHEME_ALIAS);
            assertThat(scheme.getScopeType()).isEqualTo(MenuScopeType.TENANT);
            assertThat(scheme.getOrganizationId()).isNull();
            assertThat(scheme.getTenantId()).isEqualTo("demo");

            assertThat(menuService.rootMenus(schemeId))
                    .singleElement()
                    .satisfies(menu -> assertThat(menu.getTitle()).isEqualTo("组织与权限"));
            assertThat(menuDao.list(Criteria.of().eq("schemeId", schemeId)))
                    .hasSize(2)
                    .allSatisfy(menu -> {
                        assertThat(menu.getId()).hasSizeLessThanOrEqualTo(STANDARD_ID_MAX_LENGTH);
                        assertThat(menu.getPlatformManaged()).isTrue();
                        assertThat(menu.getPlatformManagedRevision()).isNotBlank();
                    });
        }
    }

    @Test
    void shouldGenerateTenantAdminSchemeIdWithinStandardIdLength() {
        String schemeId = DefaultTenantMenuProvisioner.tenantAdminSchemeId("demo");

        assertThat(schemeId).startsWith("tenant_menu_");
        assertThat(schemeId).hasSizeLessThanOrEqualTo(STANDARD_ID_MAX_LENGTH);
    }

    @Test
    void shouldRepairCopiedTenantMenuStructureWhenSystemMenuMoves() {
        when(moduleService.resolveVisibleModule("iam.user")).thenReturn(module("iam.user"));
        createSystemAdminMenuTree();
        provisioner.afterTenantCreated("demo");

        try (TenantContext.Scope ignored = TenantContext.system("test")) {
            Menu business = new Menu();
            business.setId("platform.menu.group.business");
            business.setSchemeId(MenuSchemeService.ADMIN_SCHEME_ID);
            business.setParentId(TreeAbility.ROOT_ID);
            business.setTitle("业务支撑");
            business.setEnabled(Boolean.TRUE);
            business.setSortOrder(2);
            menuService.insert(business);

            Menu systemUserMenu = menuService.list(Criteria.of()
                            .eq("schemeId", MenuSchemeService.ADMIN_SCHEME_ID)
                            .eq("moduleAlias", "iam.user"))
                    .getFirst();
            systemUserMenu.setParentId(business.getId());
            menuService.update(systemUserMenu);
        }

        provisioner.afterTenantCreated("demo");

        String schemeId = DefaultTenantMenuProvisioner.tenantAdminSchemeId("demo");
        try (TenantContext.Scope ignored = TenantContext.use("demo")) {
            Menu copiedBusiness = menuService.list(Criteria.of()
                            .eq("schemeId", schemeId)
                            .eq("title", "业务支撑"))
                    .getFirst();
            Menu copiedUser = menuService.list(Criteria.of()
                            .eq("schemeId", schemeId)
                            .eq("moduleAlias", "iam.user"))
                    .getFirst();

            assertThat(copiedUser.getParentId()).isEqualTo(copiedBusiness.getId());
        }
    }

    @Test
    void shouldReconcileCopiedMenuWhenSystemModuleAliasChanges() {
        when(moduleService.resolveVisibleModule("iam.user")).thenReturn(module("iam.user"));
        when(moduleService.resolveVisibleModule("iam.position")).thenReturn(module("iam.position"));
        createSystemAdminMenuTree();
        provisioner.afterTenantCreated("demo");

        try (TenantContext.Scope ignored = TenantContext.system("test")) {
            Menu systemMenu = menuService.list(Criteria.of()
                            .eq("schemeId", MenuSchemeService.ADMIN_SCHEME_ID)
                            .eq("moduleAlias", "iam.user"))
                    .getFirst();
            systemMenu.setModuleAlias("iam.position");
            systemMenu.setRoute(null);
            systemMenu.setPageMode(MenuPageMode.LIST);
            menuService.update(systemMenu);
        }

        provisioner.reconcileTenantAdminMenus("demo");

        String schemeId = DefaultTenantMenuProvisioner.tenantAdminSchemeId("demo");
        try (TenantContext.Scope ignored = TenantContext.use("demo")) {
            assertThat(menuService.list(Criteria.of().eq("schemeId", schemeId)
                            .eq("moduleAlias", "iam.position")))
                    .singleElement()
                    .satisfies(menu -> {
                        assertThat(menu.getRoute()).isNull();
                        assertThat(menu.getPageMode()).isEqualTo(MenuPageMode.LIST);
                    });
        }
    }

    @Test
    void shouldIdempotentlyUpgradeManagedTenantCopyFromLegacyRouteToModuleEntry() {
        when(moduleService.resolveVisibleModule("iam.user")).thenReturn(module("iam.user"));
        when(moduleService.resolveVisibleModule("iam.organization")).thenReturn(module("iam.organization"));
        createSystemAdminMenuTree();
        try (TenantContext.Scope ignored = TenantContext.system("test")) {
            Menu organization = new Menu();
            organization.setId("platform.menu.module.iam.organization");
            organization.setSchemeId(MenuSchemeService.ADMIN_SCHEME_ID);
            organization.setParentId("platform.menu.group.identity");
            organization.setTitle("机构管理");
            organization.setOpenMode(MenuOpenMode.TAB);
            organization.setModuleAlias("iam.organization");
            organization.setRoute("/iam/organizations");
            organization.setEnabled(Boolean.TRUE);
            organization.setSortOrder(2);
            menuService.insert(organization);
        }
        provisioner.afterTenantCreated("demo");

        try (TenantContext.Scope ignored = TenantContext.system("test")) {
            Menu systemOrganization = menuService.list(Criteria.of()
                            .eq("schemeId", MenuSchemeService.ADMIN_SCHEME_ID)
                            .eq("moduleAlias", "iam.organization"))
                    .getFirst();
            systemOrganization.setRoute(null);
            systemOrganization.setPageMode(MenuPageMode.LIST);
            systemOrganization.setTitle("组织管理");
            systemOrganization.setSortOrder(9);
            menuService.update(systemOrganization);
        }

        provisioner.reconcileTenantAdminMenus("demo");

        String schemeId = DefaultTenantMenuProvisioner.tenantAdminSchemeId("demo");
        try (TenantContext.Scope ignored = TenantContext.use("demo")) {
            Menu copiedOrganization = menuService.list(Criteria.of().eq("schemeId", schemeId)
                            .eq("moduleAlias", "iam.organization"))
                    .getFirst();
            assertThat(copiedOrganization.getRoute()).isNull();
            assertThat(copiedOrganization.getPageMode()).isEqualTo(MenuPageMode.LIST);
            assertThat(copiedOrganization.getTitle()).isEqualTo("组织管理");
            assertThat(copiedOrganization.getSortOrder()).isEqualTo(9);
            assertThat(copiedOrganization.getPlatformManaged()).isTrue();

            menuDao.resetUpdateCount();
            provisioner.reconcileTenantAdminMenus("demo");
            assertThat(menuDao.updateCount()).isZero();
        }
    }

    @Test
    void shouldLeaveTenantTakenOverMenuUntouchedDuringReconciliation() {
        when(moduleService.resolveVisibleModule("iam.user")).thenReturn(module("iam.user"));
        createSystemAdminMenuTree();
        provisioner.afterTenantCreated("demo");

        String schemeId = DefaultTenantMenuProvisioner.tenantAdminSchemeId("demo");
        try (TenantContext.Scope ignored = TenantContext.use("demo")) {
            Menu copiedUser = menuService.list(Criteria.of().eq("schemeId", schemeId).eq("moduleAlias", "iam.user"))
                    .getFirst();
            copiedUser.setPlatformManaged(Boolean.FALSE);
            copiedUser.setRoute("/tenant-owned-users");
            menuService.update(copiedUser);
        }

        provisioner.reconcileTenantAdminMenus("demo");

        try (TenantContext.Scope ignored = TenantContext.use("demo")) {
            assertThat(menuService.list(Criteria.of().eq("schemeId", schemeId).eq("moduleAlias", "iam.user")))
                    .singleElement()
                    .satisfies(menu -> {
                        assertThat(menu.getPlatformManaged()).isFalse();
                        assertThat(menu.getRoute()).isEqualTo("/tenant-owned-users");
                    });
        }
    }

    @Test
    void shouldPropagateDisabledSystemBaselineToManagedTenantCopy() {
        when(moduleService.resolveVisibleModule("iam.user")).thenReturn(module("iam.user"));
        createSystemAdminMenuTree();
        provisioner.afterTenantCreated("demo");

        try (TenantContext.Scope ignored = TenantContext.system("test")) {
            Menu systemUser = menuService.list(Criteria.of()
                            .eq("schemeId", MenuSchemeService.ADMIN_SCHEME_ID)
                            .eq("moduleAlias", "iam.user"))
                    .getFirst();
            systemUser.setEnabled(Boolean.FALSE);
            menuService.update(systemUser);
        }

        provisioner.reconcileTenantAdminMenus("demo");

        try (TenantContext.Scope ignored = TenantContext.use("demo")) {
            assertThat(menuService.list(Criteria.of().eq("schemeId",
                            DefaultTenantMenuProvisioner.tenantAdminSchemeId("demo"))
                            .eq("moduleAlias", "iam.user")))
                    .singleElement()
                    .satisfies(menu -> assertThat(menu.getEnabled()).isFalse());
        }
    }

    private void createSystemAdminMenuTree() {
        try (TenantContext.Scope ignored = TenantContext.system("test")) {
            MenuScheme scheme = new MenuScheme();
            scheme.setId(MenuSchemeService.ADMIN_SCHEME_ID);
            scheme.setAlias(MenuSchemeService.ADMIN_SCHEME_ALIAS);
            scheme.setScopeType(MenuScopeType.SYSTEM);
            scheme.setTitle("平台超管");
            scheme.setEnabled(Boolean.TRUE);
            schemeService.insert(scheme);

            Menu group = new Menu();
            group.setId("platform.menu.group.identity");
            group.setSchemeId(MenuSchemeService.ADMIN_SCHEME_ID);
            group.setParentId(TreeAbility.ROOT_ID);
            group.setTitle("组织与权限");
            group.setEnabled(Boolean.TRUE);
            group.setSortOrder(1);
            menuService.insert(group);

            Menu user = new Menu();
            user.setId("platform.menu.module.iam.user");
            user.setSchemeId(MenuSchemeService.ADMIN_SCHEME_ID);
            user.setParentId(group.getId());
            user.setTitle("用户");
            user.setOpenMode(MenuOpenMode.TAB);
            user.setModuleAlias("iam.user");
            user.setEnabled(Boolean.TRUE);
            user.setSortOrder(1);
            menuService.insert(user);
        }
    }

    private PlatformModule module(String moduleAlias) {
        PlatformModule module = new PlatformModule();
        module.setAlias(moduleAlias);
        module.setApplicationAlias(moduleAlias.substring(0, moduleAlias.indexOf('.')));
        module.setModuleKind(ModuleKind.STATIC);
        module.setEnabled(Boolean.TRUE);
        return module;
    }

    private static class MenuSchemeMemoryDao extends TestMemoryDao<MenuScheme> implements MenuSchemeDao {
    }

    private static class MenuMemoryDao extends TestMemoryDao<Menu> implements MenuDao {
        private int updateCount;

        @Override
        public int updateById(Menu entity) {
            updateCount += 1;
            return super.updateById(entity);
        }

        int updateCount() {
            return updateCount;
        }

        void resetUpdateCount() {
            updateCount = 0;
        }
    }
}
