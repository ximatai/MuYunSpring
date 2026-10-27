package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.iam.federation.ConditionalOnExternalIdentityFederation;
import net.ximatai.muyun.spring.iam.federation.IdentityProvider;
import net.ximatai.muyun.spring.iam.federation.IdentityProviderService;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.PageTemplates;
import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.platform.web.UiFormula;
import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.WebSupport;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Platform super-administrator catalogue of external identity providers. */
@RestController
@ConditionalOnExternalIdentityFederation
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class,
        alias = IdentityProviderService.MODULE_ALIAS, title = "外部身份源")
@PlatformMenu(parent = PlatformMenuGroups.SETTINGS, title = "外部身份源", order = 30)
@RequestMapping("/iam.identity_provider")
public class IdentityProviderWebController extends WebSupport<IdentityProviderService> implements
        CrudWeb<IdentityProvider, IdentityProviderService>,
        SystemScope<IdentityProviderService>,
        StaticModuleUiContributor {
    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(IdentityProviderService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("外部身份源").titleField("title").secondaryField("id"))
                        .detail(detail -> detail.editor(form -> form.title("外部身份源")
                                .group("identity", "身份源信息", "协议类型与企业标识决定外部身份的归属。", group -> group
                                        .field("id", field -> field.label("身份源编码").required()
                                                .enabledWhen(UiFormula.booleanExpression("!(PRESENT({version}))")))
                                        .field("title", field -> field.label("身份源名称").required())
                                        .field("providerType", field -> field.label("协议类型").required())
                                        .field("externalEnterpriseId", field -> field.label("外部企业标识"))
                                        .field("identityNamespace", field -> field.label("身份命名空间（留空使用身份源编码）").columnSpan(2)))
                                .group("credentials", "应用凭据", "仅填写密钥的环境变量名；密钥值由部署环境提供。", group -> group
                                        .field("clientId", field -> field.label("应用 Client ID").columnSpan(2))
                                        .field("clientSecretEnvironmentVariable", field -> field.label("密钥环境变量名").columnSpan(2)))
                                .group("protocol", "协议扩展配置", "按当前协议约定填写配置项，不在此保存密钥。", group -> group
                                        .field("configuration", field -> field.label("协议配置（JSON）").uiType("textarea").columnSpan(2)))
                                .field("enabled", field -> field.label("启用状态").enabledStatus())))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud().enabledLifecycle()))))
                .build();
    }
}
