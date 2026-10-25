package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.iam.application.IamApplication;
import net.ximatai.muyun.spring.iam.federation.ConditionalOnExternalIdentityFederation;
import net.ximatai.muyun.spring.iam.federation.TenantIdentityPolicy;
import net.ximatai.muyun.spring.iam.federation.TenantIdentityPolicyService;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.PageActionAnchor;
import net.ximatai.muyun.spring.platform.web.PageTemplates;
import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.platform.web.StaticModuleWebControllerAdapter;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant administrators choose which identity providers their users may sign in or bind with. */
@RestController
@ConditionalOnExternalIdentityFederation
@PlatformStaticModule(
        application = IamApplication.class,
        alias = TenantIdentityPolicyService.MODULE_ALIAS,
        title = "外部登录方式")
@PlatformMenu(parent = PlatformMenuGroups.SETTINGS, title = "外部登录方式", order = 31)
@RequestMapping("/iam.tenant_identity_policy")
public class TenantIdentityPolicyWebController
        extends StaticModuleWebControllerAdapter<TenantIdentityPolicyService>
        implements CrudWeb<TenantIdentityPolicy, TenantIdentityPolicyService>,
                StaticModuleUiContributor {
    @PostMapping("/{id}/preview")
    @CustomActionEndpoint(
            value = "preview",
            title = "预览通讯录",
            level = PlatformActionLevel.RECORD,
            pageInvocable = true)
    public String preview(@PathVariable String id) {
        return webScope(() -> service().synchronize(id, true));
    }

    @PostMapping("/{id}/synchronize")
    @CustomActionEndpoint(
            value = "synchronize",
            title = "立即同步",
            level = PlatformActionLevel.RECORD,
            pageInvocable = true)
    public String synchronize(@PathVariable String id) {
        return webScope(() -> service().synchronize(id, false));
    }
    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(TenantIdentityPolicyService.MODULE_ALIAS)
                .pageAction("preview", PageActionAnchor.DETAIL)
                .pageAction("synchronize", PageActionAnchor.DETAIL)
                .page(
                        PageTemplates.flatManagement(
                                page ->
                                        page.explorer(
                                                        explorer ->
                                                                explorer.title("外部登录方式")
                                                                        .titleField(
                                                                                "providerTitle"))
                                                .detail(
                                                        detail ->
                                                                detail.editor(
                                                                        form ->
                                                                                form.title("外部登录方式")
                                                                                        .field(
                                                                                                "providerId",
                                                                                                field ->
                                                                                                        field.label(
                                                                                                                        "外部身份源")
                                                                                                                .required())
                                                                                        .field(
                                                                                                "loginEnabled",
                                                                                                field ->
                                                                                                        field
                                                                                                                .label(
                                                                                                                        "允许扫码登录"))
                                                                                        .field(
                                                                                                "bindingEnabled",
                                                                                                field ->
                                                                                                        field
                                                                                                                .label(
                                                                                                                        "允许账号绑定"))
                                                                                        .field(
                                                                                                "provisioningMode",
                                                                                                field ->
                                                                                                        field.label(
                                                                                                                        "未绑定时处理")
                                                                                                                .select())
                                                                                        .field(
                                                                                                "syncRoots",
                                                                                                field ->
                                                                                                        field.label(
                                                                                                                        "同步根映射 JSON")
                                                                                                                .uiType(
                                                                                                                        "textarea")
                                                                                                                .columnSpan(
                                                                                                                        2))
                                                                                        .field(
                                                                                                "syncScopeConfirmed",
                                                                                                field ->
                                                                                                        field
                                                                                                                .label(
                                                                                                                        "确认空范围或全企业根"))
                                                                                        .field(
                                                                                                "syncPositionId",
                                                                                                field ->
                                                                                                        field
                                                                                                                .label(
                                                                                                                        "同步默认岗位"))
                                                                                        .field(
                                                                                                "syncRoleId",
                                                                                                field ->
                                                                                                        field
                                                                                                                .label(
                                                                                                                        "同步默认任职角色"))
                                                                                        .field(
                                                                                                "syncExpression",
                                                                                                field ->
                                                                                                        field
                                                                                                                .label(
                                                                                                                        "同步计划（cron）"))
                                                                                        .field(
                                                                                                "syncZoneId",
                                                                                                field ->
                                                                                                        field
                                                                                                                .label(
                                                                                                                        "同步时区"))
                                                                                        .field(
                                                                                                "syncEnabled",
                                                                                                field ->
                                                                                                        field
                                                                                                                .label(
                                                                                                                        "启用定时同步"))
                                                                                        .field(
                                                                                                "enabled",
                                                                                                field ->
                                                                                                        field.label(
                                                                                                                        "启用状态")
                                                                                                                .enabledStatus())))
                                                .traits(
                                                        traits ->
                                                                traits.operations(
                                                                        operations ->
                                                                                operations
                                                                                        .standardCrud()
                                                                                        .enabledLifecycle()))))
                .build();
    }
}
