package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.StaticModuleOpenApi;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.PageTemplates;
import net.ximatai.muyun.spring.platform.web.NavigatorListQueryMode;
import net.ximatai.muyun.spring.platform.web.PageContextScopePolicy;
import net.ximatai.muyun.spring.platform.web.PageNavigatorSingleResultPolicy;
import net.ximatai.muyun.spring.platform.web.PageNavigatorSourceScope;
import net.ximatai.muyun.spring.platform.web.StaticModuleWebControllerAdapter;
import net.ximatai.muyun.spring.platform.web.StaticModuleTenantScopePolicy;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.platform.web.ViewDefinition;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.web.WebQueryCondition;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.iam.user.PasswordPolicyRule;
import net.ximatai.muyun.spring.iam.user.PasswordPolicyRuleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.ArrayList;

@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class,
        alias = PasswordPolicyRuleService.MODULE_ALIAS, title = "密码策略规则")
@StaticModuleOpenApi
@StaticModuleTenantScopePolicy(requireActiveTenant = false)
@RequestMapping("/iam.password_policy_rule")
public class PasswordPolicyRuleWebController extends StaticModuleWebControllerAdapter<PasswordPolicyRuleService> implements
        CrudWeb<PasswordPolicyRule, PasswordPolicyRuleService>,
        StaticModuleUiContributor {

    /** The password-security editor needs the complete scoped rule set for an atomic draft. */
    @Override
    public boolean supportsUnpagedQuery() {
        return true;
    }

    /**
     * Supplies an authoritative, compact snapshot for the IAM password-preview
     * assistant. Typing remains entirely browser-local; this endpoint is only
     * used to refresh the rule set when that assistant enters all-rules mode.
     */
    @GetMapping("/active-global-rules")
    @ActionEndpoint(PlatformAction.QUERY)
    public List<PasswordPolicyPreviewRuleSnapshot> activeGlobalRuleSnapshots() {
        return webScope(() -> net.ximatai.muyun.spring.web.WebOutputSupport.records(
                        service(), service().activeGlobalRules(), net.ximatai.muyun.spring.common.security.FieldOutputContext.LIST).stream()
                .map(PasswordPolicyPreviewRuleSnapshot::from)
                .toList());
    }

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(PasswordPolicyRuleService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .navigator(navigator -> navigator
                                .level("tenant", level -> level
                                        .microList("iam.tenant", "租户", "搜索租户")
                                        .sourceScope(PageNavigatorSourceScope.CURRENT_TENANT)
                                        .singleResultPolicy(PageNavigatorSingleResultPolicy.AUTO_SELECT_AND_HIDE))
                                .filterListByNavigator("tenant", "tenantId", NavigatorListQueryMode.OPTIONAL_FILTER)
                                .prefillFormFromNavigator("tenant", "tenantId"))
                        .explorer(explorer -> explorer.title("密码规则")
                                .secondaryField("message")
                                .searchPlaceholder("搜索规则名称、正则或提示")
                                .emptyDescription("暂无密码规则")
                                .recordLabel("密码规则")
                                .fallbackTitle("未命名规则")
                                .titleField("title")
                                .mutedWhenDisabled())
                        .detail(detail -> detail
                                .emptyDescription("请选择密码规则，或新建密码规则")
                                .createTitle("新建密码规则")
                                .showSystemInfo(false)
                                .display(this::displayFields)
                                .editor(form -> editorFields(form.title("密码规则"))))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud().enabledLifecycle()))))
                .build();
    }

    @Override
    @PostMapping("/query")
    @ActionEndpoint(PlatformAction.QUERY)
    public net.ximatai.muyun.spring.web.WebPageResponse<PasswordPolicyRule> query(
            @RequestBody(required = false) WebQueryRequest request) {
        return CrudWeb.super.query(queryForCurrentWorkspace(request));
    }

    /** With no selected tenant, the system workspace manages platform rules only. */
    WebQueryRequest queryForCurrentWorkspace(WebQueryRequest request) {
        if (PageContextScopePolicy.hasContextValue("tenant")) return request;
        if (request != null && request.externalQueryValues().get("tenantId") instanceof String tenant
                && !tenant.isBlank()) return request;
        if (!CurrentUserContext.isSystem()) return request;
        List<WebQueryCondition> conditions = new ArrayList<>(request == null ? List.of() : request.conditions());
        conditions.add(new WebQueryCondition("tenantId", "NULL", List.of()));
        if (request == null) return new WebQueryRequest(null, conditions, List.of());
        return new WebQueryRequest(request.page(), request.unpaged(), conditions, request.criteria(), request.queryForm(),
                request.sorts(), request.uiConfigId(), request.queryTemplateId(), request.externalQueryValues(),
                request.navigationSession(), request.quickSearch(), request.quickSearchFields(), request.navigationQueryKey(),
                request.navigatorHostModuleAlias(), request.navigatorTargetLevelKey());
    }

    private ViewDefinition.Builder displayFields(ViewDefinition.Builder form) {
        return form
                .field("title", field -> field.label("规则名称"))
                .field("pattern", field -> field.label("正则表达式").columnSpan(2))
                .field("message", field -> field.label("失败提示").columnSpan(2))
                .field("sortOrder", field -> field.label("排序号"))
                .field("scopeTypeTitle", field -> field.label("作用范围"))
                .field("description", field -> field.label("说明").uiType("textarea").columnSpan(2));
    }

    private ViewDefinition.Builder editorFields(ViewDefinition.Builder form) {
        return form
                .field("title", field -> field.label("规则名称").required())
                .field("pattern", field -> field.label("正则表达式").required().columnSpan(2))
                .field("message", field -> field.label("失败提示").required().columnSpan(2))
                .field("sortOrder", field -> field.label("排序号"))
                .field("description", field -> field.label("说明").uiType("textarea").columnSpan(2));
    }

}
