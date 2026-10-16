package net.ximatai.muyun.spring.demo.quality.configuration;

import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.demo.quality.equipment.EquipmentCategoryService;
import net.ximatai.muyun.spring.demo.quality.product.ProductService;
import net.ximatai.muyun.spring.platform.menu.*;
import net.ximatai.muyun.spring.platform.module.*;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.List;

/** Restore this optional application's menus after code cleanup and before existing-tenant copies. */
@Component @Profile("collaboration-demo")
public class QualityDemoMountTask implements PlatformBootstrapTask {
    private final PlatformModuleService modules;
    private final MenuService menus;
    public QualityDemoMountTask(PlatformModuleService modules, MenuService menus) {
        this.modules = modules; this.menus = menus;
    }
    @Override public String name() { return "demo-quality-mount"; }
    @Override public int order() { return 106; }
    @Override public void run() {
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("quality-demo-mount", "Quality Demo Mount"));
             var tenant = TenantContext.system("mount quality collaboration sample")) {
            PlatformManagedMutationContext.runAsPlatformManaged(() -> {
                String alias = QualityDemoBootstrapTask.MODULE_ALIAS;
                if (modules.select(alias) == null) {
                    var module = new PlatformModule();
                    module.setAlias(alias); module.setApplicationAlias("quality");
                    module.setModuleKind(ModuleKind.DYNAMIC); module.setTitle("质量异常单");
                    modules.insert(module);
                }
                for (String module : List.of(EquipmentCategoryService.MODULE_ALIAS, ProductService.MODULE_ALIAS, alias)) {
                    if (modules.select(module) == null) throw new IllegalStateException("Missing quality sample module: " + module);
                    modules.enable(module);
                }
                String menuId = "platform.menu.module." + alias;
                if (menus.select(menuId) == null) {
                    var menu = new Menu();
                    menu.setId(menuId); menu.setSchemeId(MenuSchemeService.ADMIN_SCHEME_ID);
                    menu.setParentId(QualityDemoMenuDeclarations.ROOT); menu.setTitle("质量异常单");
                    menu.setModuleAlias(alias); menu.setOpenMode(MenuOpenMode.TAB); menu.setSystemManaged(true);
                    menu.setSortOrder(130); menus.insert(menu);
                }
                menus.enable(QualityDemoMenuDeclarations.ROOT);
                for (String module : List.of(EquipmentCategoryService.MODULE_ALIAS, ProductService.MODULE_ALIAS, alias))
                    menus.enable("platform.menu.module." + module);
            });
        }
    }
}
