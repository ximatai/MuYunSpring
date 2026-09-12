package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Disables system menu baselines whose code declaration was removed, preserving their audit history. */
public class PlatformMenuContributionReconciliationTask implements PlatformBootstrapTask {
    private static final String LEGACY_SECURITY_AUDIT_GROUP_ID = "platform.menu.group.security";

    private final MenuService menuService;
    private final PlatformMenuInitialDataDeclarationProvider menuDeclarations;

    public PlatformMenuContributionReconciliationTask(MenuService menuService,
                                                       PlatformMenuInitialDataDeclarationProvider menuDeclarations) {
        this.menuService = Objects.requireNonNull(menuService, "menuService must not be null");
        this.menuDeclarations = Objects.requireNonNull(menuDeclarations, "menuDeclarations must not be null");
    }

    @Override
    public String name() {
        return "platform.menu-contribution-reconciliation";
    }

    @Override
    public int order() {
        return 105;
    }

    @Override
    public void run() {
        Set<String> declaredIds = menuDeclarations.declaredMenuIds();
        try (TenantContext.Scope ignored = TenantContext.system("reconcile stale platform menus")) {
            PlatformManagedMutationContext.runAsPlatformManaged(() -> {
                menuService.list(Criteria.of().eq("schemeId", MenuSchemeService.ADMIN_SCHEME_ID)
                                .eq("systemManaged", Boolean.TRUE))
                        .stream()
                        .filter(menu -> !declaredIds.contains(menu.getId()))
                        .filter(menu -> !Boolean.FALSE.equals(menu.getEnabled()))
                        .forEach(menu -> menuService.disable(menu.getId()));
                migrateLegacyBusinessLogTitles();
                retireLegacySecurityAuditGroup();
            });
        }
    }

    /**
     * Applies this release's requested renames only to the old shipped titles. Menu titles are normally
     * operator-owned, so a locally customized title must remain untouched.
     */
    private void migrateLegacyBusinessLogTitles() {
        List.of(
                new MenuTitleMigration("platform.menu.module.iam.login_audit_log", "登录审计", "登录日志"),
                new MenuTitleMigration("platform.menu.module.platform.business_activity_log", "业务活动日志", "操作日志"),
                new MenuTitleMigration("platform.menu.module.platform.request_error_log", "接口异常日志", "异常日志")
        ).forEach(migration -> {
            var menu = menuService.select(migration.menuId());
            if (menu != null
                    && Boolean.TRUE.equals(menu.getSystemManaged())
                    && migration.legacyTitle().equals(menu.getTitle())) {
                menu.setTitle(migration.currentTitle());
                menuService.update(menu);
            }
        });
    }

    /**
     * Moves any remaining menu entries out of the retired group before removing the group itself.
     * The current baseline moves password management through its declaration; this fallback preserves
     * administrator-added entries on upgrades instead of leaving them orphaned.
     */
    private void retireLegacySecurityAuditGroup() {
        var legacyGroup = menuService.select(LEGACY_SECURITY_AUDIT_GROUP_ID);
        if (legacyGroup == null
                || !MenuSchemeService.ADMIN_SCHEME_ID.equals(legacyGroup.getSchemeId())) {
            return;
        }
        menuService.children(MenuSchemeService.ADMIN_SCHEME_ID, LEGACY_SECURITY_AUDIT_GROUP_ID)
                .forEach(menu -> {
                    menu.setParentId(MenuService.ADMIN_SETTINGS_GROUP_ID);
                    menuService.update(menu);
                });
        menuService.delete(legacyGroup.getId());
    }

    private record MenuTitleMigration(String menuId, String legacyTitle, String currentTitle) {
    }
}
