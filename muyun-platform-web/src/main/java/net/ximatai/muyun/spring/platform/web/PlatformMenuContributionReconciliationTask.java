package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;

import java.util.Objects;
import java.util.Set;

/** Disables system menu baselines whose code declaration was removed, preserving their audit history. */
public class PlatformMenuContributionReconciliationTask implements PlatformBootstrapTask {
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
            PlatformManagedMutationContext.runAsPlatformManaged(() -> menuService
                    .list(Criteria.of().eq("schemeId", MenuSchemeService.ADMIN_SCHEME_ID)
                            .eq("systemManaged", Boolean.TRUE))
                    .stream()
                    .filter(menu -> !declaredIds.contains(menu.getId()))
                    .filter(menu -> !Boolean.FALSE.equals(menu.getEnabled()))
                    .forEach(menu -> menuService.disable(menu.getId())));
        }
    }
}
