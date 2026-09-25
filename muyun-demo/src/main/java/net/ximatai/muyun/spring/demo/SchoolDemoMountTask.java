package net.ximatai.muyun.spring.demo;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.demo.school.configuration.TeachingDemoMenuGroups;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.menu.MenuOpenMode;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;

import java.util.List;

/** Mounts the complete demo after declaration cleanup and before tenant menu provisioning. */
public class SchoolDemoMountTask implements PlatformBootstrapTask {
    public static final String MENU_ID = "platform.menu.module." + ExamDemoBootstrapTask.MODULE_ALIAS;

    public static final List<String> MODULE_ALIASES = List.of(
            "education.student", "education.classroom", "education.teacher",
            "education.subject_category", ExamDemoBootstrapTask.MODULE_ALIAS);

    private final MenuService menuService;
    private final PlatformModuleService moduleService;

    public SchoolDemoMountTask(MenuService menuService, PlatformModuleService moduleService) {
        this.menuService = menuService;
        this.moduleService = moduleService;
    }

    @Override
    public String name() {
        return "demo-school-mount";
    }

    @Override
    public int order() {
        return 106;
    }

    @Override
    public void run() {
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.systemUser("demo-school-mount", "School Demo Mount"));
             TenantContext.Scope ignoredTenant = TenantContext.system("mount school demonstration")) {
            PlatformManagedMutationContext.runAsPlatformManaged(() -> {
                ensureExamModule();
                for (String alias : MODULE_ALIASES) {
                    if (moduleService.select(alias) == null) {
                        throw new IllegalStateException("Missing school demo module: " + alias);
                    }
                    moduleService.enable(alias);
                }
                registerExamMenu();
                menuService.enable(TeachingDemoMenuGroups.ROOT);
                for (String alias : MODULE_ALIASES) {
                    menuService.enable("platform.menu.module." + alias);
                }
            });
        }
    }

    private void ensureExamModule() {
        if (moduleService.select(ExamDemoBootstrapTask.MODULE_ALIAS) != null) {
            return;
        }
        PlatformModule module = new PlatformModule();
        module.setAlias(ExamDemoBootstrapTask.MODULE_ALIAS);
        module.setApplicationAlias("education");
        module.setModuleKind(ModuleKind.DYNAMIC);
        module.setTitle("考试管理");
        moduleService.insert(module);
    }

    private void registerExamMenu() {
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
        menu.setSortOrder(240);
        if (exists) menuService.update(menu);
        else menuService.insert(menu);
    }
}
