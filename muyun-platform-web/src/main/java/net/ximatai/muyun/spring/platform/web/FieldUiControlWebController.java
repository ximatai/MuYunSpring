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
                    .field("defaultFieldSpecAlias", field -> field.label("默认字段规格").recordPicker())
                    .field("rendererType", field -> field.label("内置渲染类型"))
                    .field("valueShape", field -> field.label("值形态").required())
                    .field("primaryValueKey", field -> field.label("主分量键")
                            .visible(UiRule.formula(UiFormula.booleanExpression("{valueShape} == 'COMPOSITE'")))
                            .required(UiRule.formula(UiFormula.booleanExpression("{valueShape} == 'COMPOSITE'"))))
                    .field("queryMode", field -> field.label("查询语义").required())
                    .field("icon", field -> field.label("图标"))));
            page.traits(traits -> traits.operations(operations -> operations.standardCrud().enabledLifecycle().recycleBin()));
        }));
        return definition
                .relation("properties", relation -> relation.aggregateChild(child -> child
                        .title("控件属性")
                        .targetEntity("field_ui_control_property")
                        .parentBinding("fieldUiControlAlias")
                        .recycleBin()))
                .relation("bindings", relation -> relation.aggregateChild(child -> child
                        .title("字段绑定")
                        .targetEntity("field_ui_control_binding")
                        .parentBinding("fieldUiControlAlias")
                        .visible(UiRule.formula(UiFormula.booleanExpression("{valueShape} == 'COMPOSITE'")))
                        .recycleBin()))
                .build();
    }
}
