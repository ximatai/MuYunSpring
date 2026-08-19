package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.platform.web.PlatformMenu;
import net.ximatai.muyun.spring.platform.web.PlatformMenuGroups;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.web.StaticModuleOpenApi;
import net.ximatai.muyun.spring.platform.web.CrudWeb;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.PageTemplates;
import net.ximatai.muyun.spring.platform.web.StaticModuleUiContributor;
import net.ximatai.muyun.spring.platform.web.ViewDefinition;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.web.WebSupport;
import net.ximatai.muyun.spring.iam.user.PasswordPolicyRule;
import net.ximatai.muyun.spring.iam.user.PasswordPolicyRuleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.iam.application.IamApplication.class,
        alias = PasswordPolicyRuleService.MODULE_ALIAS, title = "密码策略规则")
@StaticModuleOpenApi
@PlatformMenu(parent = PlatformMenuGroups.SECURITY_AUDIT, title = "密码管理", order = 10)
@RequestMapping("/iam.password_policy_rule")
public class PasswordPolicyRuleWebController extends WebSupport<PasswordPolicyRuleService> implements
        CrudWeb<PasswordPolicyRule, PasswordPolicyRuleService>,
        SystemScope<PasswordPolicyRuleService>,
        StaticModuleUiContributor {

    /**
     * Supplies an authoritative, compact snapshot for the IAM password-preview
     * assistant. Typing remains entirely browser-local; this endpoint is only
     * used to refresh the rule set when that assistant enters all-rules mode.
     */
    @GetMapping("/active-global-rules")
    @ActionEndpoint(PlatformAction.QUERY)
    public List<PasswordPolicyPreviewRuleSnapshot> activeGlobalRuleSnapshots() {
        return webScope(() -> service().activeGlobalRules().stream()
                .map(PasswordPolicyPreviewRuleSnapshot::from)
                .toList());
    }

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(PasswordPolicyRuleService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .explorer(explorer -> explorer.title("密码规则")
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
                        .traits(traits -> traits.standardCrud().enabledStatus())))
                .build();
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
