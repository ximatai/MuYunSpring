package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.web.RecordActionWebRequest;
import net.ximatai.muyun.spring.web.SystemScope;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = PlatformModuleActionService.MODULE_ALIAS, title = "平台模块动作")
@RequestMapping("/platform.module_action")
public class PlatformModuleActionWebController
        extends StaticModuleWebControllerAdapter<PlatformModuleActionService>
        implements CrudWeb<PlatformModuleAction, PlatformModuleActionService>,
        SystemScope<PlatformModuleActionService>,
        StaticModuleUiContributor {

    /** Manual action binding depends on the dynamic executor registry, so it remains a dedicated workspace flow. */
    private static final UiFormula PLATFORM_MANAGED_ACTION =
            UiFormula.booleanExpression("{systemManaged} == true");

    private static final ModuleUiNavigatorKey MODULE_NAVIGATOR = ModuleUiNavigatorKey.of("module");
    private static final ModuleUiField ID = ModuleUiField.of("id");
    private static final ModuleUiField MODULE_ALIAS = ModuleUiField.of("moduleAlias");
    private static final ModuleUiField ACTION_CODE = ModuleUiField.of("actionCode");
    private static final ModuleUiField TITLE = ModuleUiField.of("title");
    private static final ModuleUiField ENTITY_ALIAS = ModuleUiField.of("entityAlias");
    private static final ModuleUiField PERMISSION_ACTION_CODE = ModuleUiField.of("permissionActionCode");
    private static final ModuleUiField CATEGORY = ModuleUiField.of("category");
    private static final ModuleUiField ACTION_LEVEL = ModuleUiField.of("actionLevel");
    private static final ModuleUiField ACCESS_MODE = ModuleUiField.of("accessMode");
    private static final ModuleUiField ACTION_AUTH = ModuleUiField.of("actionAuth");
    private static final ModuleUiField DATA_AUTH = ModuleUiField.of("dataAuth");
    private static final ModuleUiField DEFAULT_GRANT_POLICY = ModuleUiField.of("defaultGrantPolicy");
    private static final ModuleUiField ACCESS_MODE_OVERRIDE = ModuleUiField.of("accessModeOverride");
    private static final ModuleUiField ACTION_AUTH_OVERRIDE = ModuleUiField.of("actionAuthOverride");
    private static final ModuleUiField DATA_AUTH_OVERRIDE = ModuleUiField.of("dataAuthOverride");
    private static final ModuleUiField DEFAULT_GRANT_POLICY_OVERRIDE = ModuleUiField.of("defaultGrantPolicyOverride");
    private static final ModuleUiField AVAILABLE_EXPRESSION = ModuleUiField.of("availableExpression");
    private static final ModuleUiField UNAVAILABLE_MESSAGE = ModuleUiField.of("unavailableMessage");
    private static final ModuleUiField EXECUTOR_TYPE = ModuleUiField.of("executorType");
    private static final ModuleUiField EXECUTOR_KEY = ModuleUiField.of("executorKey");
    private static final ModuleUiField SOURCE_TYPE = ModuleUiField.of("sourceType");
    private static final ModuleUiField BINDING_ALIAS = ModuleUiField.of("bindingAlias");
    private static final ModuleUiField SYSTEM_MANAGED = ModuleUiField.of("systemManaged");
    private static final ModuleUiField ENABLED = ModuleUiField.of("enabled");

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        return ModuleUiDefinition.builder(PlatformModuleActionService.MODULE_ALIAS)
                .page(PageTemplates.flatManagement(page -> page
                        .navigator(navigator -> navigator
                                .level(MODULE_NAVIGATOR, level -> level
                                        .microList(PlatformModuleService.MODULE_ALIAS, "模块", "搜索模块"))
                                .filterListByNavigator(MODULE_NAVIGATOR, MODULE_ALIAS,
                                        NavigatorListQueryMode.REQUIRED_SCOPE)
                                .prefillFormFromNavigator(MODULE_NAVIGATOR, MODULE_ALIAS))
                        .explorer(explorer -> explorer.title("模块动作")
                                .searchPlaceholder("搜索动作名称或编码")
                                .emptyDescription("当前模块暂无动作")
                                .recordLabel("动作")
                                .fallbackTitle("未命名动作")
                                .titleField(TITLE.name())
                                .secondaryField(ACTION_CODE.name())
                                .mutedWhenDisabled())
                        .detail(detail -> detail
                                .emptyDescription("请选择动作，或新建自定义动作")
                                .createTitle("新建动作")
                                .display(form -> form.title("动作信息")
                                        .field(ACTION_CODE, field -> field.label("动作编码"))
                                        .field(TITLE, field -> field.label("动作名称"))
                                        .field(CATEGORY, field -> field.label("动作类别"))
                                        .field(ACTION_LEVEL, field -> field.label("执行层级"))
                                        .field(ACCESS_MODE, field -> field.label("访问方式"))
                                        .field(EXECUTOR_TYPE, field -> field.label("执行器类型"))
                                        .field(EXECUTOR_KEY, field -> field.label("执行实现"))
                                        .field(ACTION_AUTH, field -> field.label("动作授权"))
                                        .field(DATA_AUTH, field -> field.label("数据授权"))
                                        .field(DEFAULT_GRANT_POLICY, field -> field.label("默认授予策略"))
                                        .field(AVAILABLE_EXPRESSION, field -> field.label("可用条件"))
                                        .field(UNAVAILABLE_MESSAGE, field -> field.label("不可用提示"))
                                        .field(SOURCE_TYPE, field -> field.label("声明来源"))
                                        .field(BINDING_ALIAS, field -> field.label("绑定实现"))
                                        .field(SYSTEM_MANAGED, field -> field.label("平台托管")))
                                .editor(form -> form.title("模块动作")
                                        .field(MODULE_ALIAS, field -> field.label("所属模块").required().hidden())
                                        .field(ACTION_CODE, field -> field.label("动作编码").required()
                                                .readOnly())
                                        .field(TITLE, field -> field.label("动作名称").required()
                                                .readOnly())
                                        .field(ENTITY_ALIAS, field -> field.label("目标实体")
                                                .readOnly())
                                        .field(PERMISSION_ACTION_CODE, field -> field.label("权限动作编码")
                                                .readOnly())
                                        .field(CATEGORY, field -> field.label("动作类别").required()
                                                .readOnly())
                                        .field(ACTION_LEVEL, field -> field.label("执行层级").required()
                                                .readOnly())
                                        .field(EXECUTOR_TYPE, field -> field.label("执行器类型").required()
                                                .readOnly())
                                        .field(EXECUTOR_KEY, field -> field.label("执行实现").required()
                                                .readOnly())
                                        .field(ACCESS_MODE, field -> field.label("访问方式").required()
                                                .readOnly())
                                        .field(ACTION_AUTH, field -> field.label("动作授权")
                                                .readOnly())
                                        .field(DATA_AUTH, field -> field.label("数据授权")
                                                .readOnly())
                                        .field(DEFAULT_GRANT_POLICY, field -> field.label("默认授予策略")
                                                .readOnly())
                                        .field(AVAILABLE_EXPRESSION, field -> field.label("可用条件").uiType("textarea")
                                                .readOnly())
                                        .field(UNAVAILABLE_MESSAGE, field -> field.label("不可用提示").uiType("textarea")
                                                .readOnly())
                                        .field(ACCESS_MODE_OVERRIDE, field -> field.label("访问方式覆盖")
                                                .overrideOf(ACCESS_MODE)
                                                .visible(UiRule.formula(PLATFORM_MANAGED_ACTION))
                                                .enabledWhen(PLATFORM_MANAGED_ACTION))
                                        .field(ACTION_AUTH_OVERRIDE, field -> field.label("动作授权覆盖")
                                                .overrideOf(ACTION_AUTH)
                                                .visible(UiRule.formula(PLATFORM_MANAGED_ACTION))
                                                .enabledWhen(PLATFORM_MANAGED_ACTION))
                                        .field(DATA_AUTH_OVERRIDE, field -> field.label("数据授权覆盖")
                                                .overrideOf(DATA_AUTH)
                                                .visible(UiRule.formula(PLATFORM_MANAGED_ACTION))
                                                .enabledWhen(PLATFORM_MANAGED_ACTION))
                                        .field(DEFAULT_GRANT_POLICY_OVERRIDE, field -> field.label("默认授予策略覆盖")
                                                .overrideOf(DEFAULT_GRANT_POLICY)
                                                .visible(UiRule.formula(PLATFORM_MANAGED_ACTION))
                                                .enabledWhen(PLATFORM_MANAGED_ACTION))
                                        .field(ENABLED, field -> field.label("启用状态").enabledStatus()
                                                .readOnly())))
                        .traits(traits -> traits.operations(operations -> operations.standardCrud().enabledLifecycle()))))
                .build();
    }

    @DeleteMapping("/{id}/permission-governance")
    @ActionEndpoint(PlatformAction.UPDATE)
    public void clearPermissionGovernance(@PathVariable String id,
                                          @RequestBody RecordActionWebRequest request) {
        service().clearPermissionGovernanceOverrides(moduleAliasFromNavigator(), id,
                request.version());
    }

    private String moduleAliasFromNavigator() {
        Object value = PageContextScopePolicy.requiredRecordScopeValues(recordScopeBindings())
                .get(MODULE_ALIAS.name());
        return PlatformNameRules.requireModuleAlias(value == null ? null : value.toString());
    }
}
