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
import org.springframework.web.bind.annotation.RequestBody;
import java.util.Map;
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
    @PostMapping("/{id}/scopeImpact")
    @CustomActionEndpoint(value = "scopeImpact", title = "查看范围变更影响", level = PlatformActionLevel.RECORD)
    public Map<String, Object> scopeImpact(@PathVariable String id) {
        return webScope(() -> service().scopeImpact(id));
    }

    public record ScopeConfirmation(String scopeId, String runId) {}

    @PostMapping("/{id}/confirmScope")
    @CustomActionEndpoint(value = "confirmScope", title = "确认范围变更", level = PlatformActionLevel.RECORD)
    public String confirmScope(@PathVariable String id, @RequestBody ScopeConfirmation request) {
        return webScope(() -> service().confirmScope(id, request.scopeId(), request.runId()));
    }

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(TenantIdentityPolicyService.MODULE_ALIAS)
                .pageAction("preview", PageActionAnchor.DETAIL)
                .pageAction("synchronize", PageActionAnchor.DETAIL)
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("外部登录方式").titleField("providerTitle"))
                        .detail(detail -> detail.editor(form -> form.title("外部登录方式")
                                .group("sign_in", "登录与绑定", "状态有效期为 1 分钟至 30 天；超期需重新核验来源状态。登录与绑定不授予业务数据权限。", group -> group
                                        .field("providerId", field -> field.label("外部身份源").required())
                                        .field("sourceStatusMaxAgeSeconds", field -> field.label("来源状态有效期").uiType("duration_seconds"))
                                        .field("loginEnabled", field -> field.label("允许扫码登录"))
                                        .field("bindingEnabled", field -> field.label("允许账号绑定")))
                                .group("admission", "未绑定身份的接入规则", "仅在允许开户时使用准入部门范围；不自动开户时不会新增账号。", group -> group
                                        .field("provisioningMode", field -> field.label("未绑定时处理").select())
                                        .field("admissionRoots", field -> field.label("准入部门子树（JSON）").uiType("textarea").columnSpan(2)))
                                .group("directory", "通讯录同步范围", "同步到所选机构、岗位与任职角色；角色的数据权限需单独配置。", group -> group
                                        .field("syncRoots", field -> field.label("同步根映射（JSON）").uiType("textarea").columnSpan(2))
                                        .field("syncPositionId", field -> field.label("同步默认岗位"))
                                        .field("syncRoleId", field -> field.label("同步默认任职角色"))
                                        .field("syncScopeConfirmed", field -> field.label("确认空范围或全企业根").columnSpan(2)))
                                .group("schedule", "定时同步", "按所选时区执行；关闭后仍可手动同步。", group -> group
                                        .field("syncExpression", field -> field.label("同步计划（Cron）").uiType("cron"))
                                        .field("syncZoneId", field -> field.label("同步时区").uiType("dictionary_dropdown"))
                                        .field("syncEnabled", field -> field.label("启用定时同步").columnSpan(2)))
                                .field("enabled", field -> field.label("启用状态").enabledStatus())))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud().enabledLifecycle()))))
                .build();
    }
}
