package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;

import net.ximatai.muyun.spring.web.SystemScope;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class,
        alias = FieldUiControlService.MODULE_ALIAS, title = "字段 UI 控件")
@PlatformMenu(parent = PlatformMenuGroups.MODELING, title = "字段 UI 控件", order = 50)
@StaticModuleOpenApi
@RequestMapping("/platform.field_ui_control")
public class FieldUiControlWebController extends StaticModuleWebControllerAdapter<FieldUiControlService> implements
        ManagedDetailRelationWeb<FieldUiControl, FieldUiControlService>,
        SystemScope<FieldUiControlService>,
        StaticModuleUiContributor {

    @Override
    public ModuleUiDefinition moduleUiDefinition() {
        ModuleUiDefinition.Builder definition = ModuleUiDefinition.builder(FieldUiControlService.MODULE_ALIAS);
        definition.page(PageTemplates.flatManagement(page -> {
            page.explorer(explorer -> explorer.title("字段 UI 控件列表").titleField("title")
                    .secondaryField("alias"));
            page.detail(detail -> detail.editor(form -> form
                    .title("字段 UI 控件")
                    .field("alias", field -> field.label("控件 alias")
                            .enabledWhen(UiFormula.booleanExpression("!(PRESENT({id}))")))
                    .field("title", field -> field.label("控件名称"))
                    .field("defaultFieldSpecAlias", field -> field.label("默认字段规格").uiType("recordPicker"))
                    .field("rendererType", field -> field.label("内置渲染类型"))
                    .field("valueShape", field -> field.label("值形态").required())
                    .field("primaryValueKey", field -> field.label("主分量键")
                            .visible(UiRule.formula(UiFormula.booleanExpression("{valueShape} == 'COMPOSITE'")))
                            .required(UiRule.formula(UiFormula.booleanExpression("{valueShape} == 'COMPOSITE'"))))
                    .field("queryMode", field -> field.label("查询语义").required())
                    .field("icon", field -> field.label("图标"))));
            page.traits(traits -> traits.standardCrud().enabledStatus().recycleBin());
        }));
        return definition
                .aggregateChildRelation("properties", "控件属性", "field_ui_control_property",
                        "fieldUiControlAlias", UiRule.constant(Boolean.TRUE), true)
                .aggregateChildRelation("bindings", "字段绑定", "field_ui_control_binding",
                        "fieldUiControlAlias", UiRule.formula(
                                UiFormula.booleanExpression("{valueShape} == 'COMPOSITE'")), true)
                .build();
    }
}
