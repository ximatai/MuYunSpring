package net.ximatai.muyun.spring.demo.quality.configuration;

import org.springframework.context.annotation.Profile;

import net.ximatai.muyun.spring.platform.initialdata.*;
import net.ximatai.muyun.spring.platform.menu.*;
import org.springframework.stereotype.Component;
import java.util.List;

@Profile("collaboration-demo")
@Component
public class QualityDemoMenuDeclarations implements InitialDataDeclarationProvider {
    public static final String ROOT = "platform.menu.group.quality-demo";
    private final MenuService menus;
    public QualityDemoMenuDeclarations(MenuService menus) { this.menus = menus; }
    @Override public String name() { return "quality.demo-menu"; }
    @Override public int order() { return 15; }
    @Override public List<InitialDataDeclaration<?>> declarations() {
        var group = new Menu();
        group.setId(ROOT); group.setSchemeId(MenuSchemeService.ADMIN_SCHEME_ID);
        group.setParentId(MenuService.ADMIN_PLATFORM_GROUP_ID); group.setTitle("质量协作样板");
        group.setEnabled(true); group.setSortOrder(80);
        return List.of(InitialDataDeclaration.reconcileManaged(menus, group));
    }
}
