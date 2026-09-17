package net.ximatai.muyun.spring.demo;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.demo.school.configuration.TeachingDemoMenuGroups;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuOpenMode;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;

/** Registers the dynamic exam page alongside the static teaching demonstrations. */
public class ExamDemoMenuBootstrapTask implements PlatformBootstrapTask {
    public static final String MENU_ID = "platform.menu.module." + ExamDemoBootstrapTask.MODULE_ALIAS;

    private final MenuService menuService;

    public ExamDemoMenuBootstrapTask(MenuService menuService) {
        this.menuService = menuService;
    }

    @Override
    public String name() {
        return "demo-academic-evaluation-menu";
    }

    @Override
    public int order() {
        return 215;
    }

    @Override
    public void run() {
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.systemUser("demo-exam-menu-bootstrap", "Exam Demo Menu Bootstrap"));
             TenantContext.Scope ignoredTenant = TenantContext.system("configure exam demo menu")) {
            Menu menu = menuService.select(MENU_ID);
            boolean exists = menu != null;
            if (!exists) {
                menu = new Menu();
                menu.setId(MENU_ID);
                menu.setSchemeId(MenuSchemeService.ADMIN_SCHEME_ID);
            }
            menu.setParentId(TeachingDemoMenuGroups.ROOT);
            menu.setTitle("考试管理");
            menu.setModuleAlias(ExamDemoBootstrapTask.MODULE_ALIAS);
            menu.setOpenMode(MenuOpenMode.TAB);
            menu.setEntryParamsJson(null);
            menu.setSystemManaged(Boolean.TRUE);
            menu.setEnabled(Boolean.TRUE);
            menu.setSortOrder(240);
            Menu desired = menu;
            PlatformManagedMutationContext.runAsPlatformManaged(() -> {
                if (exists) menuService.update(desired);
                else menuService.insert(desired);
            });
        }
    }
}
