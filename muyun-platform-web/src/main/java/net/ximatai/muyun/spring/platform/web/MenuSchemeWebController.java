package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;

import net.ximatai.muyun.spring.platform.menu.MenuScheme;
import net.ximatai.muyun.spring.platform.menu.MenuSchemeService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = MenuSchemeService.MODULE_ALIAS, title = "平台菜单方案",
        route = "/platform/menu-scheme")
@StaticModuleOpenApi
@PlatformMenu(parent = PlatformMenuGroups.SETTINGS, title = "菜单方案", order = 10)
@RequestMapping("/platform.menu_scheme")
public class MenuSchemeWebController extends net.ximatai.muyun.spring.web.WebSupport<MenuSchemeService>
        implements CrudWeb<MenuScheme, MenuSchemeService> {
}
