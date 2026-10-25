package net.ximatai.muyun.spring.platform.web.job;

import net.ximatai.muyun.spring.platform.application.PlatformApplication;
import net.ximatai.muyun.spring.platform.job.*;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.*;
import net.ximatai.muyun.spring.web.SystemScope;
import org.springframework.web.bind.annotation.*;

@RestController
@PlatformStaticModule(application = PlatformApplication.class, alias = SystemActionWhitelistService.MODULE_ALIAS, title = "系统身份定时调用白名单")
@PlatformMenu(parent = PlatformMenuGroups.OPS, title = "系统身份定时调用白名单", order = 43)
@RequestMapping("/platform.system_action_whitelist")
public class SystemActionWhitelistWebController extends StaticModuleWebControllerAdapter<SystemActionWhitelistService>
        implements CrudWeb<SystemActionWhitelist, SystemActionWhitelistService>, SystemScope<SystemActionWhitelistService>, StaticModuleUiContributor {
    @Override public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(SystemActionWhitelistService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page.quickSearch("moduleAlias", "actionCode")
                        .explorer(explorer -> explorer.title("系统身份定时调用白名单").titleField("moduleAlias").secondaryField("actionCode"))
                        .detail(detail -> detail.editor(form -> form.title("系统身份定时调用白名单")
                                .field("moduleAlias", field -> field.label("目标模块").required())
                                .field("actionCode", field -> field.label("动作编码").required())))))
                .build();
    }
}
