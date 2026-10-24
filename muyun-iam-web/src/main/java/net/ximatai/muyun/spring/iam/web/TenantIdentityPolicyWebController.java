package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.iam.federation.ConditionalOnExternalIdentityFederation;
import net.ximatai.muyun.spring.iam.federation.TenantIdentityPolicy;
import net.ximatai.muyun.spring.iam.federation.TenantIdentityPolicyService;
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

/** Tenant administrators choose which identity providers their users may sign in or bind with. */
@RestController
@ConditionalOnExternalIdentityFederation
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class,
        alias = TenantIdentityPolicyService.MODULE_ALIAS, title = "外部登录方式")
@PlatformMenu(parent = PlatformMenuGroups.SETTINGS, title = "外部登录方式", order = 31)
@RequestMapping("/iam.tenant_identity_policy")
public class TenantIdentityPolicyWebController extends StaticModuleWebControllerAdapter<TenantIdentityPolicyService>
        implements CrudWeb<TenantIdentityPolicy, TenantIdentityPolicyService>, StaticModuleUiContributor {
    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(TenantIdentityPolicyService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("外部登录方式").titleField("providerTitle"))
                        .detail(detail -> detail.editor(form -> form.title("外部登录方式")
                                .field("providerId", field -> field.label("外部身份源").required())
                                .field("loginEnabled", field -> field.label("允许扫码登录"))
                                .field("bindingEnabled", field -> field.label("允许账号绑定"))
                                .field("provisioningMode", field -> field.label("未绑定时处理").select())
                                .field("enabled", field -> field.label("启用状态").enabledStatus())))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud().enabledLifecycle()))))
                .build();
    }
}
