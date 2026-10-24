package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.iam.federation.ConditionalOnExternalIdentityFederation;
import net.ximatai.muyun.spring.iam.federation.ExternalIdentity;
import net.ximatai.muyun.spring.iam.federation.ExternalIdentityService;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.PageTemplates;
import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.platform.web.StaticModuleWebControllerAdapter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrators review external identity bindings. The page is read-only because bindings are never created
 * here; removing one goes through the standard delete action.
 */
@RestController
@ConditionalOnExternalIdentityFederation
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class,
        alias = ExternalIdentityService.MODULE_ALIAS, title = "外部身份绑定")
@PlatformMenu(parent = PlatformMenuGroups.IDENTITY, title = "外部身份绑定", order = 70)
@RequestMapping("/iam.external_identity")
public class ExternalIdentityWebController extends StaticModuleWebControllerAdapter<ExternalIdentityService>
        implements CrudWeb<ExternalIdentity, ExternalIdentityService>, StaticModuleUiContributor {
    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(ExternalIdentityService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("外部身份绑定").titleField("externalUserId")
                                .secondaryField("providerTitle"))
                        .detail(detail -> detail.display(view -> view
                                .field("userTitle", field -> field.label("本地账号"))
                                .field("providerTitle", field -> field.label("外部身份源"))
                                .field("externalUserId", field -> field.label("外部用户标识"))
                                .field("displayName", field -> field.label("外部显示名"))
                                .field("bindingSourceTitle", field -> field.label("绑定来源"))
                                .field("boundAt", field -> field.label("绑定时间"))
                                .field("lastAuthenticatedAt", field -> field.label("最近登录"))))))
                .build();
    }
}
