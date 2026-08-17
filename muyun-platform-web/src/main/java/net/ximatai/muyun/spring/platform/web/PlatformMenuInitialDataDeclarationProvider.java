package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;

import net.ximatai.muyun.spring.platform.initialdata.InitialDataDeclaration;
import net.ximatai.muyun.spring.platform.initialdata.InitialDataDeclarationProvider;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuPageMode;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class PlatformMenuInitialDataDeclarationProvider implements InitialDataDeclarationProvider {
    private final MenuService menuService;
    private final ApplicationContext applicationContext;

    public PlatformMenuInitialDataDeclarationProvider(MenuService menuService,
                                                      ApplicationContext applicationContext) {
        this.menuService = menuService;
        this.applicationContext = applicationContext;
    }

    @Override
    public String name() {
        return "platform.menu-contributions";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public List<InitialDataDeclaration<?>> declarations() {
        return contributedMenus(MenuSchemeService.ADMIN_SCHEME_ID);
    }

    /** Stable identities of all code-declared system menu entries visible to this application. */
    Set<String> declaredMenuIds() {
        return menuContributions().stream()
                .map(contribution -> menuId(contribution.module(), contribution.menu()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private List<InitialDataDeclaration<?>> contributedMenus(String schemeId) {
        List<InitialDataDeclaration<?>> declarations = new ArrayList<>();
        for (MenuContribution contribution : menuContributions()) {
            declarations.add(moduleMenu(schemeId, contribution.module(), contribution.menu()));
        }
        return declarations;
    }

    private List<MenuContribution> menuContributions() {
        List<MenuContribution> contributions = new ArrayList<>();
        for (String beanName : applicationContext.getBeanNamesForAnnotation(PlatformMenu.class)) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> beanClass = AopUtils.getTargetClass(bean);
            PlatformMenu menu = AnnotationUtils.findAnnotation(beanClass, PlatformMenu.class);
            if (menu == null) {
                continue;
            }
            PlatformStaticModule module = AnnotationUtils.findAnnotation(beanClass, PlatformStaticModule.class);
            if (module == null) {
                throw new IllegalStateException("@PlatformMenu requires @PlatformStaticModule: " + beanClass.getName());
            }
            contributions.add(new MenuContribution(module, menu));
        }
        return contributions;
    }

    private InitialDataDeclaration<Menu> moduleMenu(String schemeId, PlatformStaticModule module, PlatformMenu menu) {
        String menuId = menuId(module, menu);
        Menu desired = new Menu();
        desired.setId(menuId);
        desired.setSchemeId(schemeId);
        String route = module.route().trim();
        String externalUrl = module.externalUrl().trim();
        validateModuleEntry(module, route, externalUrl);
        desired.setOpenMode(menu.openMode());
        desired.setParentId(menu.parent());
        desired.setTitle(menu.title().isBlank() ? module.title() : menu.title().trim());
        desired.setModuleAlias(module.alias());
        desired.setSystemManaged(Boolean.TRUE);
        desired.setRoute(route.isBlank() ? null : route);
        desired.setExternalUrl(externalUrl.isBlank() ? null : externalUrl);
        desired.setPageMode(route.isBlank() && externalUrl.isBlank() ? MenuPageMode.LIST : null);
        desired.setEnabled(menu.enabled());
        desired.setSortOrder(menu.order());
        return InitialDataDeclaration.reconcileManaged(menuService, desired);
    }

    private void validateModuleEntry(PlatformStaticModule module, String route, String externalUrl) {
        if (!route.isBlank() && !externalUrl.isBlank()) {
            throw new IllegalStateException("@PlatformStaticModule cannot declare both route and externalUrl: "
                    + module.alias());
        }
    }

    private String moduleMenuId(String moduleAlias) {
        return "platform.menu.module." + moduleAlias;
    }

    private String menuId(PlatformStaticModule module, PlatformMenu menu) {
        return menu.id().isBlank() ? moduleMenuId(module.alias()) : menu.id();
    }

    private record MenuContribution(PlatformStaticModule module, PlatformMenu menu) {
    }
}
