package net.ximatai.muyun.spring.demo.school.configuration;

import net.ximatai.muyun.spring.platform.initialdata.InitialDataDeclaration;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataDeclarationProvider;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.menu.MenuService;

import java.util.List;

/** Creates a profile-scoped parent menu so teaching examples never mix with platform business support. */
public class TeachingDemoMenuInitialDataDeclarationProvider implements InitialDataDeclarationProvider {
    private final MenuService menuService;

    public TeachingDemoMenuInitialDataDeclarationProvider(MenuService menuService) {
        this.menuService = menuService;
    }

    @Override
    public String name() {
        return "education.demo-menu-group";
    }

    @Override
    public int order() {
        // The platform baseline creates its top-level Platform group at order 13;
        // module-menu contributions run at 20 and therefore see this parent first.
        return 15;
    }

    @Override
    public List<InitialDataDeclaration<?>> declarations() {
        Menu group = new Menu();
        group.setId(TeachingDemoMenuGroups.ROOT);
        group.setSchemeId(MenuSchemeService.ADMIN_SCHEME_ID);
        group.setParentId(MenuService.ADMIN_PLATFORM_GROUP_ID);
        group.setTitle("教学演示");
        group.setEnabled(Boolean.TRUE);
        group.setSortOrder(70);
        return List.of(InitialDataDeclaration.reconcileManaged(menuService, group));
    }
}
